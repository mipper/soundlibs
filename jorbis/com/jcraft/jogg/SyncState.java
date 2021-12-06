/* -*-mode:java; c-basic-offset:2; indent-tabs-mode:nil -*- */
/* JOrbis
 * Copyright (C) 2000 ymnk, JCraft,Inc.
 *
 * Written by: 2000 ymnk<ymnk@jcraft.com>
 *
 * Many thanks to
 *   Monty <monty@xiph.org> and
 *   The XIPHOPHORUS Company http://www.xiph.org/ .
 * JOrbis has been based on their awesome works, Vorbis codec.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Library General Public License
 * as published by the Free Software Foundation; either version 2 of
 * the License, or (at your option) any later version.

 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Library General Public License for more details.
 *
 * You should have received a copy of the GNU Library General Public
 * License along with this program; if not, write to the Free Software
 * Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
 */

package com.jcraft.jogg;

// DECODING PRIMITIVES: packet streaming layer

// This has two layers to place more of the multi-serialno and paging
// control in the application's hands.  First, we expose a data buffer
// using ogg_decode_buffer().  The app either copies into the
// buffer, or passes it directly to read(), etc.  We then call
// ogg_decode_wrote() to tell how many bytes we just added.
//
// Pages are returned (pointers into the buffer in ogg_sync_state)
// by ogg_decode_stream().  The page is then submitted to
// ogg_decode_page() along with the appropriate
// ogg_stream_state* (ie, matching serialno).  We then get raw
// packets out calling ogg_stream_packet() with a
// ogg_stream_state.  See the 'frame-prog.txt' docs for details and
// example code.

public class SyncState {

    private final Page _pageseek = new Page();
    private final byte[] _chksum = new byte[4];
    public byte[] _data;
    int _storage;  // Total capacity of data[]
    int _fill;
    int _returned;
    int _unsynced;
    int _headerbytes;
    int _bodybytes;

    public int clear() {
        _data = null;
        return 0;
    }

    /**
     * Ensure we have a buffer with size space available.  Any previously read data
     * that has not been returned already will be preserved.
     *
     * @param size Size we must be able to accommodate.
     * @return Index of the first empty byte in our buffer.
     */
    public int buffer(final int size) {
        // first, clear out any space that has been previously returned
        if (_returned != 0) {
            _fill -= _returned;
            if (_fill > 0) {
                System.arraycopy(_data, _returned, _data, 0, _fill);
            }
            _returned = 0;
        }

        if (size > _storage - _fill) {
            // We need to extend the internal buffer
            final int newsize = size + _fill + 4096; // an extra page to be nice
            if (_data != null) {
                final byte[] foo = new byte[newsize];
                System.arraycopy(_data, 0, foo, 0, _data.length);
                _data = foo;
            }
            else {
                _data = new byte[newsize];
            }
            _storage = newsize;
        }

        return _fill;
    }

    public int wrote(final int bytes) {
        if (_fill + bytes > _storage) {
            return -1;
        }
        _fill += bytes;
        return 0;
    }

    /**
     * sync the stream.  This is meant to be useful for finding page
     * boundaries.
     * <p>
     * return values for this:
     * -n) skipped n bytes
     * 0) page not ready; more data (no bytes skipped)
     * n) page synced at current location; page length n bytes
     *
     * @see https://en.wikipedia.org/wiki/Ogg#Page_structure
     */
    public int pageseek(final Page og) {
        int page = _returned;
        int next;
        int bytes = _fill - _returned;

        if (_headerbytes == 0) {
            if (bytes < 27) {
                return 0; // not enough for a header
            }

            /* verify capture pattern */
            if (_data[page] != 'O' || _data[page + 1] != 'g' || _data[page + 2] != 'g' || _data[page + 3] != 'S') {
                _headerbytes = 0;
                _bodybytes = 0;

                // search for possible capture
                next = 0;
                for (int ii = 0; ii < bytes - 1; ii++) {
                    if (_data[page + 1 + ii] == 'O') {
                        next = page + 1 + ii;
                        break;
                    }
                }
                //next=memchr(page+1,'O',bytes-1);
                if (next == 0) {
                    next = _fill;
                }

                _returned = next;
                return -(next - page);
            }
            // Total size of the header including the segment table
            final int totalHeaderSize = (_data[page + 26] & 0xff) + 27;
            if (bytes < totalHeaderSize) {
                return 0; // not enough for header + seg table
            }

            // count up body length in the segment table
            for (int i = 0; i < (_data[page + 26] & 0xff); i++) {
                _bodybytes += _data[page + 27 + i] & 0xff;
            }
            _headerbytes = totalHeaderSize;
        }

        if (_bodybytes + _headerbytes > bytes) {
            return 0;
        }

        // The whole test page is buffered.  Verify the checksum
        synchronized (_chksum) {
            // Grab the checksum bytes, set the header field to zero

            System.arraycopy(_data, page + 22, _chksum, 0, 4);
            _data[page + 22] = 0;
            _data[page + 23] = 0;
            _data[page + 24] = 0;
            _data[page + 25] = 0;

            // set up a temp page struct and recompute the checksum
            final Page log = _pageseek;
            log.header_base = _data;
            log.header = page;
            log.header_len = _headerbytes;

            log.body_base = _data;
            log.body = page + _headerbytes;
            log.body_len = _bodybytes;
            log.checksum();

            // Compare
            if (_chksum[0] != _data[page + 22]
                || _chksum[1] != _data[page + 23]
                || _chksum[2] != _data[page + 24]
                || _chksum[3] != _data[page + 25]) {
                // D'oh.  Mismatch! Corrupt page (or miscapture and not a page at all)
                // replace the computed checksum with the one actually read in
                System.arraycopy(_chksum, 0, _data, page + 22, 4);
                // Bad checksum. Lose sync */

                _headerbytes = 0;
                _bodybytes = 0;
                // search for possible capture
                next = 0;
                for (int ii = 0; ii < bytes - 1; ii++) {
                    if (_data[page + 1 + ii] == 'O') {
                        next = page + 1 + ii;
                        break;
                    }
                }
                //next=memchr(page+1,'O',bytes-1);
                if (next == 0) {
                    next = _fill;
                }
                _returned = next;
                return -(next - page);
            }
        }

        // yes, have a whole page all ready to go
        page = _returned;

        if (og != null) {
            og.header_base = _data;
            og.header = _returned;
            og.header_len = _headerbytes;
            og.body_base = _data;
            og.body = _returned + _headerbytes;
            og.body_len = _bodybytes;
        }

        _unsynced = 0;
        _returned += bytes = _headerbytes + _bodybytes;
        _headerbytes = 0;
        _bodybytes = 0;
        return bytes;
    }

    // sync the stream and get a page.  Keep trying until we find a page.
    // Supress 'sync errors' after reporting the first.
    //
    // return values:
    //  -1) recapture (hole in data)
    //   0) need more data
    //   1) page returned
    //
    // Returns pointers into buffered data; invalidated by next call to
    // _stream, _clear, _init, or _buffer

    public int pageout(final Page og) {
        // all we need to do is verify a page at the head of the stream
        // buffer.  If it doesn't verify, we look for the next potential
        // frame

        while (true) {
            final int ret = pageseek(og);
            if (ret > 0) {
                // have a page
                return 1;
            }
            if (ret == 0) {
                // need more data
                return 0;
            }

            // head did not start a synced page... skipped some bytes
            if (_unsynced == 0) {
                _unsynced = 1;
                return -1;
            }
            // loop. keep looking
        }
    }

    // clear things to an initial state.  Good to call, eg, before seeking
    public int reset() {
        _fill = 0;
        _returned = 0;
        _unsynced = 0;
        _headerbytes = 0;
        _bodybytes = 0;
        return 0;
    }

    public void init() {
    }

    public int getDataOffset() {
        return _returned;
    }

    public int getBufferOffset() {
        return _fill;
    }

}
