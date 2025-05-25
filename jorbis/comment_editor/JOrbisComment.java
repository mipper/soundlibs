/* JOrbisComment -- pure Java Ogg Vorbis Comment Editor
 *
 * Copyright (C) 2000 ymnk, JCraft,Inc.
 *
 * Written by: 2000 ymnk<ymnk@jcraft.com>
 *
 * Many thanks to
 *   Monty <monty@xiph.org> and
 *   The XIPHOPHORUS Company http://www.xiph.org/ .
 * JOrbis has been based on their awesome works, Vorbis codec and
 * JOrbisPlayer depends on JOrbis.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
 */

import com.jcraft.jogg.Packet;
import com.jcraft.jogg.Page;
import com.jcraft.jogg.StreamState;
import com.jcraft.jogg.SyncState;
import com.jcraft.jorbis.Comment;
import com.jcraft.jorbis.Info;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Properties;

class JOrbisComment {

    private static final int CHUNKSIZE = 4096;
    State state;

    JOrbisComment(final State state) {
        this.state = state;
    }

    public static void main(final String[] arg) {
        final String input = arg[0];
        final String output = (arg.length > 1 ? arg[1] : null);

        InputStream in = null;
        try {
            in = new FileInputStream(input);
        }
        catch (final Exception e) {
            System.out.println(e);
        }
        final State foo = new State();

        final JOrbisComment jorbiscomment = new JOrbisComment(foo);

        jorbiscomment.read(in);

        System.out.println(foo.vc);

        if (output == null) {
            return;
        }

        final Properties props = System.getProperties();
        int i = 0;
        String comment = null;
        while (true) {
            try {
                comment = (String) props.get("JOrbis.comment." + new Integer(i));
                foo.vc.add(comment);
                i++;
            }
            catch (final Exception e) {
                break;
            }
        }

        // foo.vc.add("TEST=TESTTEST");
        // foo.vc.add_tag("TITLE", "demodemo");

        System.out.println(foo.vc);

        //System.out.println(foo.vc.query("TEST"));
        //System.out.println(foo.vc.query("TITLE"));
        //System.out.println(foo.vc.query("ARTIST"));

        try {
            final OutputStream out = new FileOutputStream(output);
            jorbiscomment.write(out);
            out.close();
        }
        catch (final Exception e) {
            e.printStackTrace();
            System.out.println(e);
        }
    }

    void read(final InputStream in) {
        state.in = in;

        final Page og = new Page();

        int index;
        byte[] buffer;
        int bytes = 0;

        state.oy = new SyncState();
        state.oy.init();

        index = state.oy.buffer(CHUNKSIZE);
        buffer = state.oy._data;
        try {
            bytes = state.in.read(buffer, index, CHUNKSIZE);
        }
        catch (final Exception e) {
            System.err.println(e);
            return;
        }
        state.oy.wrote(bytes);

        if (state.oy.pageout(og) != 1) {
            if (bytes < CHUNKSIZE) {
                System.err.println("Input truncated or empty.");
            }
            else {
                System.err.println("Input is not an Ogg bitstream.");
            }
            // goto err;
            return;
        }
        state.serial = og.serialno();
        state.os = new StreamState();
        state.os.init(state.serial);
        //  os.reset();

        state.vi = new Info();
        state.vi.init();

        state.vc = new Comment();
        state.vc.init();

        if (state.os.pagein(og) < 0) {
            System.err.println("Error reading first page of Ogg bitstream data.");
            // goto err
            return;
        }

        final Packet header_main = new Packet();

        if (state.os.packetout(header_main) != 1) {
            System.err.println("Error reading initial header packet.");
            // goto err
            return;
        }

        if (state.vi.synthesis_headerin(state.vc, header_main) < 0) {
            System.err.println("This Ogg bitstream does not contain Vorbis data.");
            // goto err
            return;
        }

        state.mainlen = header_main.bytes;
        state.mainbuf = new byte[state.mainlen];
        System.arraycopy(header_main.packet_base, header_main.packet, state.mainbuf, 0, state.mainlen);

        int i = 0;
        Packet header;
        final Packet header_comments = new Packet();
        final Packet header_codebooks = new Packet();

        header = header_comments;
        while (i < 2) {
            while (i < 2) {
                int result = state.oy.pageout(og);
                if (result == 0) {
                    break; /* Too little data so far */
                }
                else if (result == 1) {
                    state.os.pagein(og);
                    while (i < 2) {
                        result = state.os.packetout(header);
                        if (result == 0) {
                            break;
                        }
                        if (result == -1) {
                            System.out.println("Corrupt secondary header.");
                            //goto err;
                            return;
                        }
                        state.vi.synthesis_headerin(state.vc, header);
                        if (i == 1) {
                            state.booklen = header.bytes;
                            state.bookbuf = new byte[state.booklen];
                            System.arraycopy(header.packet_base, header.packet, state.bookbuf, 0, header.bytes);
                        }
                        i++;
                        header = header_codebooks;
                    }
                }
            }

            index = state.oy.buffer(CHUNKSIZE);
            buffer = state.oy._data;
            try {
                bytes = state.in.read(buffer, index, CHUNKSIZE);
            }
            catch (final Exception e) {
                System.err.println(e);
                return;
            }

            if (bytes == 0 && i < 2) {
                System.out.println("EOF before end of vorbis headers.");
                // goto err;
                return;
            }
            state.oy.wrote(bytes);
        }

        System.out.println(state.vi);
    }

    int write(final OutputStream out) {
        final StreamState streamout = new StreamState();
        final Packet header_main = new Packet();
        final Packet header_comments = new Packet();
        final Packet header_codebooks = new Packet();

        final Page ogout = new Page();

        final Packet op = new Packet();
        long granpos = 0;

        int result;

        int index;
        byte[] buffer;

        int bytes, eosin = 0;
        int needflush = 0, needout = 0;

        header_main.bytes = state.mainlen;
        header_main.packet_base = state.mainbuf;
        header_main.packet = 0;
        header_main.b_o_s = 1;
        header_main.e_o_s = 0;
        header_main.granulepos = 0;

        header_codebooks.bytes = state.booklen;
        header_codebooks.packet_base = state.bookbuf;
        header_codebooks.packet = 0;
        header_codebooks.b_o_s = 0;
        header_codebooks.e_o_s = 0;
        header_codebooks.granulepos = 0;

        streamout.init(state.serial);

        state.vc.header_out(header_comments);

        streamout.packetin(header_main);
        streamout.packetin(header_comments);
        streamout.packetin(header_codebooks);

        //System.out.println("%1");

        while ((result = streamout.flush(ogout)) != 0) {
            //System.out.println("result="+result);
            try {
                out.write(ogout.header_base, ogout.header, ogout.header_len);
                out.flush();
            }
            catch (final Exception e) {
                //goto cleanup;
                break;
            }
            try {
                out.write(ogout.body_base, ogout.body, ogout.body_len);
                out.flush();
            }
            catch (final Exception e) {
                //goto cleanup;
                break;
            }
        }

        //System.out.println("%2");

        while (state.fetch_next_packet(op) != 0) {
            final int size = state.blocksize(op);
            granpos += size;
            //System.out.println("#1");
            if (needflush != 0) {
                //System.out.println("##1");
                if (streamout.flush(ogout) != 0) {
                    try {
                        out.write(ogout.header_base, ogout.header, ogout.header_len);
                        out.flush();
                    }
                    catch (final Exception e) {
                        e.printStackTrace();
                        //goto cleanup;
                        return -1;
                    }
                    try {
                        out.write(ogout.body_base, ogout.body, ogout.body_len);
                        out.flush();
                    }
                    catch (final Exception e) {
                        e.printStackTrace();
                        //System.out.println("ogout.body_base.length="+ogout.body_base.length+
                        //                   ", ogout.body="+ogout.body+
                        //                   ", ogout.body_len="+ogout.body_len);
                        //goto cleanup;
                        return -1;
                    }
                }
            }
            //System.out.println("%2 eosin="+eosin);
            else if (needout != 0) {
                //System.out.println("##2");
                if (streamout.pageout(ogout) != 0) {
                    try {
                        out.write(ogout.header_base, ogout.header, ogout.header_len);
                        out.flush();
                    }
                    catch (final Exception e) {
                        e.printStackTrace();
                        //goto cleanup;
                        return -1;
                    }
                    try {
                        out.write(ogout.body_base, ogout.body, ogout.body_len);
                        out.flush();
                    }
                    catch (final Exception e) {
                        e.printStackTrace();
                        //System.out.println("ogout.body_base.length="+ogout.body_base.length+
                        //                   ", ogout.body="+ogout.body+
                        //                   ", ogout.body_len="+ogout.body_len);
                        //goto cleanup;
                        return -1;
                    }
                }
            }

            //System.out.println("#2");

            needflush = needout = 0;

            if (op.granulepos == -1) {
                op.granulepos = granpos;
                streamout.packetin(op);
            }
            else {
                if (granpos > op.granulepos) {
                    granpos = op.granulepos;
                    streamout.packetin(op);
                    needflush = 1;
                }
                else {
                    streamout.packetin(op);
                    needout = 1;
                }
            }
            //System.out.println("#3");
        }

        //System.out.println("%3");

        streamout.e_o_s = 1;
        while (streamout.flush(ogout) != 0) {
            try {
                out.write(ogout.header_base, ogout.header, ogout.header_len);
                out.flush();
            }
            catch (final Exception e) {
                e.printStackTrace();
                //goto cleanup;
                return -1;
            }
            try {
                out.write(ogout.body_base, ogout.body, ogout.body_len);
                out.flush();
            }
            catch (final Exception e) {
                e.printStackTrace();
                //System.out.println("ogout.body_base.length="+ogout.body_base.length+
                //                   ", ogout.body="+ogout.body+
                //                   ", ogout.body_len="+ogout.body_len);
                //goto cleanup;
                return -1;
            }
        }

        //System.out.println("%4");

        state.vi.clear();
        //System.out.println("%3 eosin="+eosin);

        //System.out.println("%5");

        eosin = 0; /* clear it, because not all paths to here do */
        while (eosin == 0) { /* We reached eos, not eof */
            /* We copy the rest of the stream (other logical streams)
             * through, a page at a time. */
            while (true) {
                result = state.oy.pageout(ogout);
                //System.out.println(" result4="+result);
                if (result == 0) {
                    break;
                }
                if (result < 0) {
                    System.out.println("Corrupt or missing data, continuing...");
                }
                else {
                    /* Don't bother going through the rest, we can just
                     * write the page out now */
                    try {
                        out.write(ogout.header_base, ogout.header, ogout.header_len);
                        out.flush();
                    }
                    catch (final Exception e) {
                        //goto cleanup;
                        return -1;
                    }
                    try {
                        out.write(ogout.body_base, ogout.body, ogout.body_len);
                        out.flush();
                    }
                    catch (final Exception e) {
                        //goto cleanup;
                        return -1;
                    }
                }
            }

            index = state.oy.buffer(CHUNKSIZE);
            buffer = state.oy._data;
            try {
                bytes = state.in.read(buffer, index, CHUNKSIZE);
            }
            catch (final Exception e) {
                System.err.println(e);
                return -1;
            }
            //System.out.println("bytes="+bytes);
            state.oy.wrote(bytes);

            if (bytes == 0 || bytes == -1) {
                eosin = 1;
                break;
            }
        }

    /*
cleanup:
	ogg_stream_clear(&streamout);
	ogg_packet_clear(&header_comments);

	free(state->mainbuf);
	free(state->bookbuf);

	jorbiscomment_clear_internals(state);
	if(!eosin)
	{
		state->lasterror =
			"Error writing stream to output. "
			"Output stream may be corrupted or truncated.";
		return -1;
	}

	return 0;
       }
    */
        return 0;
    }

}


class State {

    private static final int CHUNKSIZE = 4096;
    SyncState oy;
    StreamState os;
    Comment vc;
    Info vi;

    InputStream in;
    int serial;
    byte[] mainbuf;
    byte[] bookbuf;
    int mainlen;
    int booklen;
    String lasterror;

    int prevW;
    Page og = new Page();

    int blocksize(final Packet p) {
        final int _this = vi.blocksize(p);
        final int ret = (_this + prevW) / 4;

        if (prevW == 0) {
            prevW = _this;
            return 0;
        }

        prevW = _this;
        return ret;
    }

    int fetch_next_packet(final Packet p) {
        final int result;
        byte[] buffer;
        int index;
        int bytes;

        result = os.packetout(p);

        if (result > 0) {
            return 1;
        }

        while (oy.pageout(og) <= 0) {
            index = oy.buffer(CHUNKSIZE);
            buffer = oy._data;
            try {
                bytes = in.read(buffer, index, CHUNKSIZE);
            }
            catch (final Exception e) {
                System.err.println(e);
                return 0;
            }
            if (bytes > 0) {
                oy.wrote(bytes);
            }
            if (bytes == 0 || bytes == -1) {
                return 0;
            }
        }
        os.pagein(og);

        return fetch_next_packet(p);
    }

}
