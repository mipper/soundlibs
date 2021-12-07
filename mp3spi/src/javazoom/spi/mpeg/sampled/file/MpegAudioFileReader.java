/*
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
 *----------------------------------------------------------------------
 */
package javazoom.spi.mpeg.sampled.file;

import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.Header;
import javazoom.spi.mpeg.sampled.file.tag.IcyInputStream;
import javazoom.spi.mpeg.sampled.file.tag.MP3Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.io.PushbackInputStream;
import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * This class implements AudioFileReader for MP3 SPI.
 */
public class MpegAudioFileReader extends TAudioFileReader {

    private static final Logger _logger = LoggerFactory.getLogger(MpegAudioFileReader.class);
    private static final String VERSION = "MP3SPI 1.9.5";
    private static final String[] id3v1genres = {"Blues", "Classic Rock", "Country", "Dance", "Disco", "Funk", "Grunge", "Hip-Hop", "Jazz", "Metal", "New Age", "Oldies", "Other", "Pop", "R&B", "Rap", "Reggae", "Rock", "Techno", "Industrial", "Alternative", "Ska", "Death Metal", "Pranks", "Soundtrack", "Euro-Techno", "Ambient", "Trip-Hop", "Vocal", "Jazz+Funk", "Fusion", "Trance", "Classical", "Instrumental", "Acid", "House", "Game", "Sound Clip", "Gospel", "Noise", "AlternRock", "Bass", "Soul", "Punk", "Space", "Meditative", "Instrumental Pop", "Instrumental Rock", "Ethnic", "Gothic", "Darkwave", "Techno-Industrial", "Electronic", "Pop-Folk", "Eurodance", "Dream", "Southern Rock", "Comedy", "Cult", "Gangsta", "Top 40", "Christian Rap", "Pop/Funk", "Jungle", "Native American", "Cabaret", "New Wave", "Psychadelic", "Rave", "Showtunes", "Trailer", "Lo-Fi", "Tribal", "Acid Punk", "Acid Jazz", "Polka", "Retro", "Musical", "Rock & Roll", "Hard Rock", "Folk", "Folk-Rock", "National Folk", "Swing", "Fast Fusion", "Bebob", "Latin", "Revival", "Celtic", "Bluegrass", "Avantgarde", "Gothic Rock", "Progressive Rock", "Psychedelic Rock", "Symphonic Rock", "Slow Rock", "Big Band", "Chorus", "Easy Listening", "Acoustic", "Humour", "Speech", "Chanson", "Opera", "Chamber Music", "Sonata", "Symphony", "Booty Brass", "Primus", "Porn Groove", "Satire", "Slow Jam", "Club", "Tango", "Samba", "Folklore", "Ballad", "Power Ballad", "Rhythmic Soul", "Freestyle", "Duet", "Punk Rock", "Drum Solo", "A Capela", "Euro-House", "Dance Hall", "Goa", "Drum & Bass", "Club-House", "Hardcore", "Terror", "Indie", "BritPop", "Negerpunk", "Polsk Punk", "Beat", "Christian Gangsta Rap", "Heavy Metal", "Black Metal", "Crossover", "Contemporary Christian", "Christian Rock", "Merengue", "Salsa", "Thrash Metal", "Anime", "JPop", "SynthPop"};
    private static final int INITIAL_READ_LENGTH;
    private static final int MARK_LIMIT;

    static {
        INITIAL_READ_LENGTH = calcInitReadLength(System.getProperty("marklimit"));
        MARK_LIMIT = INITIAL_READ_LENGTH + 1;
    }

    private static int calcInitReadLength(final String markLimit) {
        if (markLimit != null) {
            try {
                return Integer.parseInt(markLimit);
            }
            catch (final NumberFormatException e) {
            }
        }
        return 128000 * 32;
    }

    private final AudioFormat.Encoding[][] _encodings = {{MpegEncoding.MPEG2L1, MpegEncoding.MPEG2L2, MpegEncoding.MPEG2L3}, {MpegEncoding.MPEG1L1, MpegEncoding.MPEG1L2, MpegEncoding.MPEG1L3}, {MpegEncoding.MPEG2DOT5L1, MpegEncoding.MPEG2DOT5L2, MpegEncoding.MPEG2DOT5L3},};
    private final boolean _weak;

    public MpegAudioFileReader() {
        super(MARK_LIMIT, true);
        _logger.debug(VERSION);
        _weak = System.getProperty("mp3spi.weak") == null;
    }

    /**
     * Returns AudioFileFormat from URL.
     */
    @Override
    public AudioFileFormat getAudioFileFormat(final URL url)
            throws UnsupportedAudioFileException, IOException {
        final long fileLength = AudioSystem.NOT_SPECIFIED;
        final URLConnection conn = url.openConnection();
        // Tell shoutcast server (if any) that SPI support shoutcast stream.
        conn.setRequestProperty("Icy-Metadata", "1");
        try (final InputStream inputStream = conn.getInputStream()) {
            return getAudioFileFormat(inputStream, fileLength);
        }
    }

    /**
     * Returns AudioFileFormat from inputstream and medialength.
     */
    @Override
    public AudioFileFormat getAudioFileFormat(final InputStream inputStream, final long mediaLength)
            throws UnsupportedAudioFileException, IOException {
        final Map<String, Object> affProperties = new HashMap<>();
        final int size = inputStream.available();
        final PushbackInputStream pis = new PushbackInputStream(inputStream, MARK_LIMIT);
        processHeader(inputStream, affProperties, pis);
        // MPEG header info.
        final int nFrequency;
        int nTotalFrames = AudioSystem.NOT_SPECIFIED;
        final float frameRate;
        final int bitRate;
        final int channels;
        final int nHeader;
        final AudioFormat.Encoding encoding;
        final Map<String, Object> afProperties = new HashMap<>();
        try {
            final Bitstream bitstream = new Bitstream(pis);
            final int streamPos = bitstream.header_pos();
            affProperties.put("mp3.header.pos", streamPos);
            final Header header = bitstream.readFrame();
            // nVersion = 0 => MPEG2-LSF (Including MPEG2.5), nVersion = 1 => MPEG1
            final int nVersion = header.version();
            if (nVersion == 2) {
                affProperties.put("mp3.version.mpeg", Float.toString(2.5f));
            }
            else {
                affProperties.put("mp3.version.mpeg", Integer.toString(2 - nVersion));
            }
            // nLayer = 1,2,3
            final int nLayer = header.layer();
            affProperties.put("mp3.version.layer", Integer.toString(nLayer));
            final int nMode = header.mode();
            affProperties.put("mp3.mode", nMode);
            channels = nMode == 3 ? 1 : 2;
            affProperties.put("mp3.channels", channels);
            final boolean nVBR = header.vbr();
            afProperties.put("vbr", nVBR);
            affProperties.put("mp3.vbr", nVBR);
            affProperties.put("mp3.vbr.scale", header.vbr_scale());
            final int frameSize = header.calculate_framesize();
            affProperties.put("mp3.framesize.bytes", frameSize);
            if (frameSize < 0) {
                throw new UnsupportedAudioFileException("Invalid FrameSize : " + frameSize);
            }
            nFrequency = header.frequency();
            affProperties.put("mp3.frequency.hz", nFrequency);
            frameRate = (float) (1.0 / header.msPerFrame() * 1000.0);
            affProperties.put("mp3.framerate.fps", frameRate);
            if (frameRate < 0) {
                throw new UnsupportedAudioFileException("Invalid FrameRate : " + frameRate);
            }
            // Remove heading tag length from real stream length.
            int tmpLength = (int) mediaLength;
            if (streamPos > 0 && mediaLength != AudioSystem.NOT_SPECIFIED && streamPos < mediaLength) {
                tmpLength -= streamPos;
            }
            if (mediaLength != AudioSystem.NOT_SPECIFIED) {
                affProperties.put("mp3.length.bytes", mediaLength);
                nTotalFrames = header.max_number_of_frames(tmpLength);
                affProperties.put("mp3.length.frames", nTotalFrames);
            }
            bitRate = header.bitrate();
            afProperties.put("bitrate", bitRate);
            affProperties.put("mp3.bitrate.nominal.bps", bitRate);
            nHeader = header.getSyncHeader();
            encoding = _encodings[nVersion][nLayer - 1];
            affProperties.put("mp3.version.encoding", encoding.toString());
            if (mediaLength != AudioSystem.NOT_SPECIFIED) {
                final int nTotalMS = Math.round(header.total_ms(tmpLength));
                affProperties.put("duration", nTotalMS * 1000L);
            }
            affProperties.put("mp3.copyright", header.copyright());
            affProperties.put("mp3.original", header.original());
            affProperties.put("mp3.crc", header.checksums());
            affProperties.put("mp3.padding", header.padding());
            final InputStream id3v2 = bitstream.getRawID3v2();
            if (id3v2 != null) {
                affProperties.put("mp3.id3tag.v2", id3v2);
                parseID3v2Frames(id3v2, affProperties);
            }
            _logger.debug(header.toString());
        }
        catch (final Exception e) {
            _logger.info("not a MPEG stream:{}", e.getMessage());
            throw new UnsupportedAudioFileException("not a MPEG stream:" + e.getMessage());
        }
        // Deeper checks ?
        final int cVersion = nHeader >> 19 & 0x3;
        if (cVersion == 1) {
            _logger.debug("not a MPEG stream: wrong version");
            throw new UnsupportedAudioFileException("not a MPEG stream: wrong version");
        }
        final int cSFIndex = nHeader >> 10 & 0x3;
        if (cSFIndex == 3) {
            _logger.debug("not a MPEG stream: wrong sampling rate");
            throw new UnsupportedAudioFileException("not a MPEG stream: wrong sampling rate");
        }
        // Look up for ID3v1 tag
        if (size == mediaLength && mediaLength != AudioSystem.NOT_SPECIFIED) {
            final FileInputStream fis = (FileInputStream) inputStream;
            final byte[] id3v1 = new byte[128];
            fis.skip(inputStream.available() - id3v1.length);
            fis.read(id3v1, 0, id3v1.length);
            if (id3v1[0] == 'T' && id3v1[1] == 'A' && id3v1[2] == 'G') {
                parseID3v1Frames(id3v1, affProperties);
            }
        }
        final AudioFormat format = new MpegAudioFormat(
                encoding,
                nFrequency,
                /* SampleSizeInBits - The size of a sample*/
                AudioSystem.NOT_SPECIFIED,
                // Channels - The number of channels
                channels,
                -1,
                /* The number of bytes in each frame*/
                frameRate, /* FrameRate - The number of frames played or recorded per second*/
                true,
                afProperties);
        return new MpegAudioFileFormat(
                MpegFileFormatType.MP3,
                format,
                nTotalFrames,
                (int) mediaLength,
                affProperties);
    }

    private void processHeader(final InputStream inputStream,
                           final Map<String, Object> affProperties,
                           final PushbackInputStream pis)
            throws IOException, UnsupportedAudioFileException {
        final byte[] head = new byte[22];
        pis.read(head);
        _logger.debug("InputStream : {} =>{}", inputStream, new String(head, StandardCharsets.UTF_8));

        // Check for WAV, AU, and AIFF, Ogg Vorbis, Flac, MAC file formats.
        // Next check for Shoutcast (supported) and OGG (unsupported) streams.
        if (head[0] == 'R'
            && head[1] == 'I'
            && head[2] == 'F'
            && head[3] == 'F'
            && head[8] == 'W'
            && head[9] == 'A'
            && head[10] == 'V'
            && head[11] == 'E') {
            _logger.debug("RIFF/WAV stream found");
            final int isPCM = head[21] << 8 & 0x0000FF00 | head[20] & 0x00000FF;
            if (_weak) {
                if (isPCM == 1) {
                    throw new UnsupportedAudioFileException("WAV PCM stream found");
                }
            }
        }
        else if (head[0] == '.'
                && head[1] == 's'
                && head[2] == 'n'
                && head[3] == 'd') {
            _logger.debug("AU stream found");
            if (_weak) {
                throw new UnsupportedAudioFileException("AU stream found");
            }
        }
        else if (head[0] == 'F'
                && head[1] == 'O'
                && head[2] == 'R'
                && head[3] == 'M'
                && head[8] == 'A'
                && head[9] == 'I'
                && head[10] == 'F'
                && head[11] == 'F') {
            _logger.debug("AIFF stream found");
            if (_weak) {
                throw new UnsupportedAudioFileException("AIFF stream found");
            }
        }
        else if (head[0] == 'M' | head[0] == 'm'
                && head[1] == 'A' | head[1] == 'a'
                && head[2] == 'C' | head[2] == 'c') {
            _logger.debug("APE stream found");
            if (_weak) {
                throw new UnsupportedAudioFileException("APE stream found");
            }
        }
        else if (head[0] == 'F' | head[0] == 'f'
                && head[1] == 'L' | head[1] == 'l'
                && head[2] == 'A' | head[2] == 'a'
                && head[3] == 'C' | head[3] == 'c') {
            _logger.debug("FLAC stream found");
            if (_weak) {
                throw new UnsupportedAudioFileException("FLAC stream found");
            }
        }
        // Shoutcast stream ?
        else if (head[0] == 'I' | head[0] == 'i'
                && head[1] == 'C' | head[1] == 'c'
                && head[2] == 'Y' | head[2] == 'y') {
            pis.unread(head);
            // Load shoutcast meta data.
            loadShoutcastInfo(pis, affProperties);
        }
        // Ogg stream ?
        else if (head[0] == 'O' | head[0] == 'o'
                && head[1] == 'G' | head[1] == 'g'
                && head[2] == 'G' | head[2] == 'g') {
            _logger.debug("Ogg stream found");
            if (_weak) {
                throw new UnsupportedAudioFileException("Ogg stream found");
            }
        }
        // No, so pushback.
        else {
            pis.unread(head);
        }
    }

    /**
     * Returns AudioInputStream from file.
     */
    @Override
    public AudioInputStream getAudioInputStream(final File file)
            throws UnsupportedAudioFileException, IOException {
        final InputStream inputStream = new FileInputStream(file);
        try {
            return getAudioInputStream(inputStream);
        }
        catch (final UnsupportedAudioFileException | IOException | RuntimeException e) {
            inputStream.close();
            throw e;
        }
    }

    /**
     * Returns AudioInputStream from url.
     */
    @Override
    public AudioInputStream getAudioInputStream(final URL url)
            throws UnsupportedAudioFileException, IOException {
        final long lFileLengthInBytes = AudioSystem.NOT_SPECIFIED;
        final URLConnection conn = url.openConnection();
        // Tell shoutcast server (if any) that SPI support shoutcast stream.
        boolean isShout = false;
        final int toRead = 4;
        final byte[] head = new byte[toRead];
        conn.setRequestProperty("Icy-Metadata", "1");
        final BufferedInputStream bInputStream = new BufferedInputStream(conn.getInputStream());
        bInputStream.mark(toRead);
        final int read = bInputStream.read(head, 0, toRead);
        if (read > 2
            && head[0] == 'I' | head[0] == 'i'
            && head[1] == 'C' | head[1] == 'c'
            && head[2] == 'Y' | head[2] == 'y') {
            isShout = true;
        }
        bInputStream.reset();
        final InputStream inputStream;
        // Is is a shoutcast server ?
        if (isShout) {
            // Yes
            final IcyInputStream icyStream = new IcyInputStream(bInputStream);
            icyStream.addTagParseListener(IcyListener.getInstance());
            inputStream = icyStream;
        }
        else {
            // No, is Icecast 2 ?
            final String metaint = conn.getHeaderField("icy-metaint");
            if (metaint != null) {
                // Yes, it might be icecast 2 mp3 stream.
                final IcyInputStream icyStream = new IcyInputStream(bInputStream, metaint);
                icyStream.addTagParseListener(IcyListener.getInstance());
                inputStream = icyStream;
            }
            else {
                // No
                inputStream = bInputStream;
            }
        }
        try {
            return getAudioInputStream(inputStream, lFileLengthInBytes);
        }
        catch (final UnsupportedAudioFileException | IOException e) {
            inputStream.close();
            throw e;
        }
    }

    /**
     * Return the AudioInputStream from the given InputStream.
     */
    @Override
    public AudioInputStream getAudioInputStream(InputStream inputStream)
            throws UnsupportedAudioFileException, IOException {
        if (!inputStream.markSupported()) {
            inputStream = new BufferedInputStream(inputStream);
        }
        return super.getAudioInputStream(inputStream);
    }

    private static void parseID3v1Frames(final byte[] frames, final Map<String, Object> props) {
        final String tag = new String(frames, 0, frames.length, StandardCharsets.ISO_8859_1);
        _logger.debug("ID3v1 frame dump='{}'", tag);
        int start = 3;
        final String titlev1 = chopSubstring(tag, start, start += 30);
        final String titlev2 = (String) props.get("title");
        if ((titlev2 == null || titlev2.isEmpty()) && titlev1 != null) {
            props.put("title", titlev1);
        }
        final String artistv1 = chopSubstring(tag, start, start += 30);
        final String artistv2 = (String) props.get("author");
        if ((artistv2 == null || artistv2.isEmpty()) && artistv1 != null) {
            props.put("author", artistv1);
        }
        final String albumv1 = chopSubstring(tag, start, start += 30);
        final String albumv2 = (String) props.get("album");
        if ((albumv2 == null || albumv2.isEmpty()) && albumv1 != null) {
            props.put("album", albumv1);
        }
        final String yearv1 = chopSubstring(tag, start, start += 4);
        final String yearv2 = (String) props.get("year");
        if ((yearv2 == null || yearv2.isEmpty()) && yearv1 != null) {
            props.put("date", yearv1);
        }
        final String commentv1 = chopSubstring(tag, start, start += 28);
        final String commentv2 = (String) props.get("comment");
        if ((commentv2 == null || commentv2.isEmpty()) && commentv1 != null) {
            props.put("comment", commentv1);
        }
        final String trackv1 = String.valueOf(frames[126] & 0xff);
        final String trackv2 = (String) props.get("mp3.id3tag.track");
        if (trackv2 == null || trackv2.isEmpty()) {
            props.put("mp3.id3tag.track", trackv1);
        }
        final int genrev1 = frames[127] & 0xff;
        if (genrev1 < id3v1genres.length) {
            final String genrev2 = (String) props.get("mp3.id3tag.genre");
            if (genrev2 == null || genrev2.isEmpty()) {
                props.put("mp3.id3tag.genre", id3v1genres[genrev1]);
            }
        }
    }

    private static String chopSubstring(final String s, final int start, final int end) {
        String str = null;
        try {
            str = s.substring(start, end);
            final int loc = str.indexOf('\0');
            if (loc != -1) {
                str = str.substring(0, loc);
            }
        }
        catch (final StringIndexOutOfBoundsException e) {
            // Skip encoding issues.
            _logger.debug("Cannot chopSubString.", e);
        }
        return str;
    }

    private static void parseID3v2Frames(final InputStream frames, final Map<String, Object> props) {
        byte[] bframes = null;
        int size;
        try {
            size = frames.available();
            bframes = new byte[size];
            frames.mark(size);
            frames.read(bframes);
            frames.reset();
        }
        catch (final IOException e) {
            _logger.debug("Cannot parse ID3v2", e);
        }
        if (!"ID3".equals(new String(bframes, 0, 3, StandardCharsets.UTF_8))) {
            _logger.debug("No ID3v2 header found!");
            return;
        }
        final int v2version = bframes[3] & 0xFF;
        props.put("mp3.id3tag.v2.version", String.valueOf(v2version));
        if (v2version < 2 || v2version > 4) {
            _logger.debug("Unsupported ID3v2 version {}!", v2version);
            return;
        }
        try {
            _logger.debug("ID3v2 frame dump='{}'", new String(bframes, StandardCharsets.UTF_8));
            String value = null;
            for (int i = 10; i < bframes.length && bframes[i] > 0; i += size) {
                if (v2version == 3 || v2version == 4) {
                    final String code = new String(bframes, i, 4, StandardCharsets.UTF_8);
                    size = extractSyncsafeInteger(bframes, i);
                    i += 10;
                    if (code.equals("TALB")
                        || code.equals("TIT2")
                        || code.equals("TYER")
                        || code.equals("TPE1")
                        || code.equals("TCOP")
                        || code.equals("COMM")
                        || code.equals("TCON")
                        || code.equals("TRCK")
                        || code.equals("TPOS")
                        || code.equals("TDRC")
                        || code.equals("TCOM")
                        || code.equals("TIT1")
                        || code.equals("TENC")
                        || code.equals("TPUB")
                        || code.equals("TPE2")
                        || code.equals("TLEN")) {
                        if (code.equals("COMM")) {
                            value = parseText(bframes, i, size, 5);
                        }
                        else {
                            value = parseText(bframes, i, size, 1);
                        }
                        if (value != null && !value.isEmpty()) {
                            if (code.equals("TALB")) {
                                props.put("album", value);
                            }
                            else if (code.equals("TIT2")) {
                                props.put("title", value);
                            }
                            else if (code.equals("TYER")) {
                                props.put("date", value);
                            }
                            // ID3v2.4 date fix.
                            else if (code.equals("TDRC")) {
                                props.put("date", value);
                            }
                            else if (code.equals("TPE1")) {
                                props.put("author", value);
                            }
                            else if (code.equals("TCOP")) {
                                props.put("copyright", value);
                            }
                            else if (code.equals("COMM")) {
                                props.put("comment", value);
                            }
                            else if (code.equals("TCON")) {
                                props.put("mp3.id3tag.genre", value);
                            }
                            else if (code.equals("TRCK")) {
                                props.put("mp3.id3tag.track", value);
                            }
                            else if (code.equals("TPOS")) {
                                props.put("mp3.id3tag.disc", value);
                            }
                            else if (code.equals("TCOM")) {
                                props.put("mp3.id3tag.composer", value);
                            }
                            else if (code.equals("TIT1")) {
                                props.put("mp3.id3tag.grouping", value);
                            }
                            else if (code.equals("TENC")) {
                                props.put("mp3.id3tag.encoded", value);
                            }
                            else if (code.equals("TPUB")) {
                                props.put("mp3.id3tag.publisher", value);
                            }
                            else if (code.equals("TPE2")) {
                                props.put("mp3.id3tag.orchestra", value);
                            }
                            else if (code.equals("TLEN")) {
                                props.put("mp3.id3tag.length", value);
                            }
                        }
                    }
                }
                else {
                    // ID3v2.2
                    final String scode = new String(bframes, i, 3, StandardCharsets.UTF_8);
                    size = 0x00000000 + (bframes[i + 3] << 16) + (bframes[i + 4] << 8) + bframes[i + 5];
                    i += 6;
                    if (scode.equals("TAL")
                        || scode.equals("TT2")
                        || scode.equals("TP1")
                        || scode.equals("TYE")
                        || scode.equals("TRK")
                        || scode.equals("TPA")
                        || scode.equals("TCR")
                        || scode.equals("TCO")
                        || scode.equals("TCM")
                        || scode.equals("COM")
                        || scode.equals("TT1")
                        || scode.equals("TEN")
                        || scode.equals("TPB")
                        || scode.equals("TP2")
                        || scode.equals("TLE")) {
                        if (scode.equals("COM")) {
                            value = parseText(bframes, i, size, 5);
                        }
                        else {
                            value = parseText(bframes, i, size, 1);
                        }
                        if (value != null && !value.isEmpty()) {
                            if (scode.equals("TAL")) {
                                props.put("album", value);
                            }
                            else if (scode.equals("TT2")) {
                                props.put("title", value);
                            }
                            else if (scode.equals("TYE")) {
                                props.put("date", value);
                            }
                            else if (scode.equals("TP1")) {
                                props.put("author", value);
                            }
                            else if (scode.equals("TCR")) {
                                props.put("copyright", value);
                            }
                            else if (scode.equals("COM")) {
                                props.put("comment", value);
                            }
                            else if (scode.equals("TCO")) {
                                props.put("mp3.id3tag.genre", value);
                            }
                            else if (scode.equals("TRK")) {
                                props.put("mp3.id3tag.track", value);
                            }
                            else if (scode.equals("TPA")) {
                                props.put("mp3.id3tag.disc", value);
                            }
                            else if (scode.equals("TCM")) {
                                props.put("mp3.id3tag.composer", value);
                            }
                            else if (scode.equals("TT1")) {
                                props.put("mp3.id3tag.grouping", value);
                            }
                            else if (scode.equals("TEN")) {
                                props.put("mp3.id3tag.encoded", value);
                            }
                            else if (scode.equals("TPB")) {
                                props.put("mp3.id3tag.publisher", value);
                            }
                            else if (scode.equals("TP2")) {
                                props.put("mp3.id3tag.orchestra", value);
                            }
                            else if (scode.equals("TLE")) {
                                props.put("mp3.id3tag.length", value);
                            }
                        }
                    }
                }
            }
        }
        catch (final RuntimeException e) {
            // Ignore all parsing errors.
            _logger.debug("Cannot parse ID3v2", e);
        }
        _logger.debug("ID3v2 parsed");
    }

    private static int extractSyncsafeInteger(final byte[] bframes, final int i) {
        return (bframes[i + 4] & 0x7f) << 21
               | (bframes[i + 5] & 0x7f) << 14
               | (bframes[i + 6] & 0x7f) << 7
               | bframes[i + 7] & 0x7f;
    }

    private static String parseText(final byte[] bframes, final int offset, final int size, final int skip) {
        String value = null;
        try {
            final String[] ENC_TYPES = {"ISO-8859-1", "UTF16", "UTF-16BE", "UTF-8"};
            value = new String(bframes, offset + skip, size - skip, ENC_TYPES[bframes[offset]]);
            value = chopSubstring(value, 0, value.length());
        }
        catch (final UnsupportedEncodingException e) {
            _logger.debug("ID3v2 Encoding error.", e);
        }
        return value;
    }

    private static void loadShoutcastInfo(final InputStream input, final Map<String, Object> props)
            throws IOException {
        final IcyInputStream icy = new IcyInputStream(new BufferedInputStream(input));
        final MP3Tag titleMP3Tag = icy.getTag("icy-name");
        if (titleMP3Tag != null) {
            props.put("title", ((String) titleMP3Tag.getValue()).trim());
        }
        final MP3Tag[] meta = icy.getTags();
        if (meta != null) {
            for (final MP3Tag mp3Tag : meta) {
                final String key = mp3Tag.getName();
                final String value = ((String) icy.getTag(key).getValue()).trim();
                props.put("mp3.shoutcast.metadata." + key, value);
            }
        }
    }

}
