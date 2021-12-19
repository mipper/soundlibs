/*
 *   VorbisAudioFileReader.
 *
 *   JavaZOOM : vorbisspi@javazoom.net
 *              http://www.javazoom.net
 *
 *   This program is free software; you can redistribute it and/or modify
 *   it under the terms of the GNU Library General Public License as published
 *   by the Free Software Foundation; either version 2 of the License, or
 *   (at your option) any later version.
 *
 *   This program is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU Library General Public License for more details.
 *
 *   You should have received a copy of the GNU Library General Public
 *   License along with this program; if not, write to the Free Software
 *   Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
 *
 */

package javazoom.spi.vorbis.sampled.file;

import com.jcraft.jogg.Packet;
import com.jcraft.jogg.Page;
import com.jcraft.jogg.StreamState;
import com.jcraft.jogg.SyncState;
import com.jcraft.jorbis.Comment;
import com.jcraft.jorbis.Info;
import com.jcraft.jorbis.JOrbisException;
import com.jcraft.jorbis.VorbisFile;
import org.slf4j.Logger;
import org.tritonus.share.sampled.file.TAudioFileReader;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.StringTokenizer;

import static org.slf4j.LoggerFactory.getLogger;

/**
 * This class implements the AudioFileReader class and provides an
 * Ogg Vorbis file reader for use with the Java Sound Service Provider Interface.
 */
public class VorbisAudioFileReader extends TAudioFileReader {

    private static final Logger _logger = getLogger(VorbisAudioFileReader.class);
    private static final int BUFFER_SIZE = 4096;
    private static final int INITIAL_READ_LENGTH = 128_000 * 32;
    private static final int MARK_LIMIT = INITIAL_READ_LENGTH + 1;
    private final Map<String, String> _tagMapping;

    private SyncState _syncState;
    private StreamState _streamState;
    private Page _page;
    private Packet _packet;
    private Info _vorbisInfo;
    private Comment _comment;
    private int _bytes;
    private InputStream _oggBitStream;


    public VorbisAudioFileReader() {
        super(MARK_LIMIT, true);
        _tagMapping = new HashMap<>();
        _tagMapping.put("genre", "ogg.comment.genre");
        _tagMapping.put("tracknumber", "ogg.comment.track");
    }

    /**
     * Return the AudioFileFormat from the given file.
     */
    @Override
    public AudioFileFormat getAudioFileFormat(final File file)
            throws UnsupportedAudioFileException, IOException {
        InputStream inputStream = null;
        try {
            inputStream = new BufferedInputStream(new FileInputStream(file));
            //            inputStream.mark(MARK_LIMIT);
            //            final AudioFileFormat aff = getAudioFileFormat(inputStream);
            //            inputStream.reset();
            // Get Vorbis file info such as length in seconds.
            final VorbisFile vf = new VorbisFile(file.getAbsolutePath());
            return getAudioFileFormat(inputStream, (int) file.length(), Math.round((vf.time_total(-1)) * 1000));
        }
        catch (final JOrbisException e) {
            throw new IOException(e.getMessage(), e);
        }
        finally {
            if (inputStream != null) {
                inputStream.close();
            }
        }
    }

    /**
     * Return the AudioFileFormat from the given URL.
     */
    @Override
    public AudioFileFormat getAudioFileFormat(final URL url)
            throws UnsupportedAudioFileException, IOException {
        final InputStream inputStream = url.openStream();
        try {
            return getAudioFileFormat(inputStream);
        }
        finally {
            if (inputStream != null) {
                inputStream.close();
            }
        }
    }

    /**
     * Return the AudioFileFormat from the given InputStream.
     */
    @Override
    public AudioFileFormat getAudioFileFormat(InputStream inputStream)
            throws UnsupportedAudioFileException, IOException {
        try {
            if (!inputStream.markSupported()) {
                inputStream = new BufferedInputStream(inputStream);
            }
            inputStream.mark(MARK_LIMIT);
            return getAudioFileFormat(inputStream, AudioSystem.NOT_SPECIFIED, AudioSystem.NOT_SPECIFIED);
        }
        finally {
            inputStream.reset();
        }
    }

    /**
     * Return the AudioFileFormat from the given InputStream and length in bytes.
     */
    @Override
    public AudioFileFormat getAudioFileFormat(final InputStream inputStream, final long medialength)
            throws UnsupportedAudioFileException, IOException {
        return getAudioFileFormat(inputStream, (int) medialength, AudioSystem.NOT_SPECIFIED);
    }

    /**
     * Return the AudioFileFormat from the given InputStream, length in bytes and length in milliseconds.
     */
    private AudioFileFormat getAudioFileFormat(final InputStream bitStream, final int mediaLength, final int totalMs)
            throws UnsupportedAudioFileException, IOException {
        final Map<String, Object> affProperties = new HashMap<>();
        final Map<String, Object> afProperties = new HashMap<>();
        if (totalMs > 0) {
            affProperties.put("duration", totalMs * 1000L);
        }
        _oggBitStream = bitStream;
        initJorbis();
        try {
            readHeaders(affProperties);
        }
        catch (final IOException ioe) {
            _logger.info(ioe.getMessage());
            throw new UnsupportedAudioFileException(ioe.getMessage());
        }

        String dmp = _vorbisInfo.toString();
        _logger.debug("Vorbis Info: {}", dmp);
        // TODO: Encapsulate bitrate and framerate
        final int ind = dmp.lastIndexOf("bitrate:");
        int minbitrate = -1;
        int nominalbitrate = -1;
        if (ind != -1) {
            dmp = dmp.substring(ind + 8);
            final StringTokenizer st = new StringTokenizer(dmp, ",");
            if (st.hasMoreTokens()) {
                minbitrate = Integer.parseInt(st.nextToken());
                if (minbitrate > 0) {
                    affProperties.put("ogg.bitrate.min.bps", minbitrate);
                }
            }
            if (st.hasMoreTokens()) {
                nominalbitrate = Integer.parseInt(st.nextToken());
                if (nominalbitrate > 0) {
                    afProperties.put("bitrate", nominalbitrate);
                    affProperties.put("ogg.bitrate.nominal.bps", nominalbitrate);
                }
            }
            if (st.hasMoreTokens()) {
                final int maxbitrate = Integer.parseInt(st.nextToken());
                if (maxbitrate > 0) {
                    affProperties.put("ogg.bitrate.max.bps", maxbitrate);
                }
            }
        }
        afProperties.put("vbr", Boolean.TRUE);
        if (_vorbisInfo.channels > 0) {
            affProperties.put("ogg.channels", _vorbisInfo.channels);
        }
        if (_vorbisInfo.rate > 0) {
            affProperties.put("ogg.frequency.hz", _vorbisInfo.rate);
        }
        if (mediaLength > 0) {
            affProperties.put("ogg.length.bytes", mediaLength);
        }
        affProperties.put("ogg.version", _vorbisInfo.version);

        //AudioFormat.Encoding encoding = VorbisEncoding.VORBISENC;
        //AudioFormat format = new VorbisAudioFormat(encoding, vorbisInfo.rate, AudioSystem.NOT_SPECIFIED, vorbisInfo.channels, AudioSystem.NOT_SPECIFIED, AudioSystem.NOT_SPECIFIED, true,af_properties);

        // Patch from MS to ensure more SPI compatibility ...
        float frameRate = -1;
        if (nominalbitrate > 0) {
            frameRate = nominalbitrate / 8.0f;
        }
        else if (minbitrate > 0) {
            frameRate = minbitrate / 8.0f;
        }

        final AudioFormat format = new VorbisAudioFormat(VorbisEncoding.VORBISENC,
                                                         _vorbisInfo.rate,
                                                         AudioSystem.NOT_SPECIFIED,
                                                         _vorbisInfo.channels,
                                                         1,
                                                         frameRate,
                                                         false,
                                                         afProperties);
        return new VorbisAudioFileFormat(VorbisFileFormatType.OGG,
                                         format,
                                         AudioSystem.NOT_SPECIFIED,
                                         mediaLength,
                                         affProperties);
    }

    /**
     * Return the AudioInputStream from the given InputStream.
     */
    @Override
    public AudioInputStream getAudioInputStream(final InputStream inputStream)
            throws UnsupportedAudioFileException, IOException {
        return getAudioInputStream(inputStream, AudioSystem.NOT_SPECIFIED, AudioSystem.NOT_SPECIFIED);
    }

    /**
     * Return the AudioInputStream from the given InputStream.
     */
    private AudioInputStream getAudioInputStream(InputStream inputStream, final int medialength, final int totalms)
            throws UnsupportedAudioFileException, IOException {
        try {
            if (!inputStream.markSupported()) {
                inputStream = new BufferedInputStream(inputStream);
            }
            inputStream.mark(MARK_LIMIT);
            final AudioFileFormat audioFileFormat = getAudioFileFormat(inputStream, medialength, totalms);
            inputStream.reset();
            return new AudioInputStream(inputStream, audioFileFormat.getFormat(), audioFileFormat.getFrameLength());
        }
        catch (final UnsupportedAudioFileException | IOException e) {
            inputStream.reset();
            throw e;
        }
    }

    /**
     * Return the AudioInputStream from the given File.
     */
    @Override
    public AudioInputStream getAudioInputStream(final File file)
            throws UnsupportedAudioFileException, IOException {
        final InputStream inputStream = new FileInputStream(file);
        try {
            return getAudioInputStream(inputStream);
        }
        catch (final UnsupportedAudioFileException | IOException e) {
            inputStream.close();
            throw e;
        }
    }

    /**
     * Return the AudioInputStream from the given URL.
     */
    @Override
    public AudioInputStream getAudioInputStream(final URL url)
            throws UnsupportedAudioFileException, IOException {
        final InputStream inputStream = url.openStream();
        try {
            return getAudioInputStream(inputStream);
        }
        catch (final UnsupportedAudioFileException | IOException e) {
            inputStream.close();
            throw e;
        }
    }

    /**
     * Reads headers and comments.
     */
    private void readHeaders(final Map<String, Object> affProperties)
            throws IOException {
        int index = _syncState.buffer(BUFFER_SIZE);
        _bytes = readFromStream(_syncState._data, index, BUFFER_SIZE);
        if (_bytes == -1) {
            throw new IOException("Cannot get any data from selected Ogg bitstream.");
        }
        _syncState.wrote(_bytes);
        if (_syncState.pageout(_page) != 1) {
            if (_bytes < BUFFER_SIZE) {
                throw new IOException("EOF");
            }
            throw new IOException("Input does not appear to be an Ogg bitstream.");
        }
        _streamState.init(_page.serialno());
        _vorbisInfo.init();
        _comment.init();
        affProperties.put("ogg.serial", _page.serialno());
        if (_streamState.pagein(_page) < 0) {
            // error; stream version mismatch perhaps
            throw new IOException("Error reading first page of Ogg bitstream data.");
        }
        if (_streamState.packetout(_packet) != 1) {
            // no page? must not be vorbis
            throw new IOException("Error reading initial header packet.");
        }
        if (_vorbisInfo.synthesis_headerin(_comment, _packet) < 0) {
            // error case; not a vorbis header
            throw new IOException("This Ogg bitstream does not contain Vorbis audio data.");
        }
        int i = 0;
        while (i < 2) {
            while (i < 2) {
                int result = _syncState.pageout(_page);
                if (result == 0) {
                    break;
                } // Need more data
                if (result == 1) {
                    _streamState.pagein(_page);
                    while (i < 2) {
                        result = _streamState.packetout(_packet);
                        if (result == 0) {
                            break;
                        }
                        if (result == -1) {
                            throw new IOException("Corrupt secondary header.  Exiting.");
                        }
                        _vorbisInfo.synthesis_headerin(_comment, _packet);
                        i++;
                    }
                }
            }
            index = _syncState.buffer(BUFFER_SIZE);
            _bytes = readFromStream(_syncState._data, index, BUFFER_SIZE);
            if (_bytes == -1) {
                break;
            }
            if (_bytes == 0 && i < 2) {
                throw new IOException("End of file before finding all Vorbis  headers!");
            }
            _syncState.wrote(_bytes);
        }
        // Read Ogg Vorbis comments.
        readTags(affProperties);
    }

    private void readTags(final Map<String, Object> affProperties) {
        final byte[][] ptr = _comment.user_comments;
        String currComment = "";
        for (final byte[] bytes : ptr) {
            if (bytes != null) {
                currComment = (new String(bytes, 0, bytes.length - 1, StandardCharsets.UTF_8)).trim();
                _logger.debug("Current Comment: {}", currComment);
                final String[] strs = currComment.split("=", 2);
                if (strs.length == 2) {
                    final String key = strs[0].toLowerCase();
                    final String mappedKey = _tagMapping.get(key);
                    affProperties.put(mappedKey != null && !mappedKey.isBlank() ? mappedKey : key, strs[1]);
                }
            }
        }
        affProperties.put(
                "ogg.comment.encodedby",
                new String(_comment.vendor, 0, _comment.vendor.length - 1, StandardCharsets.UTF_8));
    }

    private static boolean tagMatches(final String currComment, final String title) {
        return currComment.toLowerCase().startsWith(title + "=");
    }

    /**
     * Reads from the oggBitStream_ a specified number of Bytes(bufferSize_) worth
     * starting at index and puts them in the specified buffer[].
     *
     * @return the number of bytes read or -1 if error.
     */
    private int readFromStream(final byte[] buffer, final int index, final int bufferSize) {
        try {
            return _oggBitStream.read(buffer, index, bufferSize);
        }
        catch (final RuntimeException | IOException e) {
            _logger.info("Error reading audio.", e);
        }
        return -1;
    }

    /**
     * Initializes all the jOrbis and jOgg vars that are used for song playback.
     */
    private void initJorbis() {
        _syncState = new SyncState();
        _streamState = new StreamState();
        _page = new Page();
        _packet = new Packet();
        _vorbisInfo = new Info();
        _comment = new Comment();
        _bytes = 0;
        _syncState.init();
    }

}
