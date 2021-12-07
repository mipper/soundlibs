/*
 *   DecodedMpegAudioInputStream.
 *
 *   JavaZOOM : mp3spi@javazoom.net
 * 				http://www.javazoom.net
 *
 *-----------------------------------------------------------------------------
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
 *------------------------------------------------------------------------
 */

package javazoom.spi.mpeg.sampled.convert;

import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.BitstreamException;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.DecoderException;
import javazoom.jl.decoder.Equalizer;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.Obuffer;
import javazoom.spi.PropertiesContainer;
import javazoom.spi.mpeg.sampled.file.IcyListener;
import javazoom.spi.mpeg.sampled.file.tag.TagParseEvent;
import javazoom.spi.mpeg.sampled.file.tag.TagParseListener;
import org.tritonus.share.TDebug;
import org.tritonus.share.sampled.convert.TAsynchronousFilteredAudioInputStream;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Main decoder.
 */
public class DecodedMpegAudioInputStream extends TAsynchronousFilteredAudioInputStream
        implements PropertiesContainer, TagParseListener {

    private final InputStream m_encodedStream;
    private final Bitstream m_bitstream;
    private final Decoder m_decoder;
    private final Equalizer m_equalizer;
    private final float[] m_equalizer_values;
    private final DMAISObuffer m_oBuffer;
    private Header m_header;
    // Bytes info.
    private long byteslength = -1;
    private long currentByte;
    // Frame info.
    private int frameslength = -1;
    private long currentFrame;
    private int currentFramesize;
    private int currentBitrate = -1;
    // Time info.
    private long currentMicrosecond;
    // Shoutcast stream info
    private final IcyListener shoutlst;


    private final HashMap properties;

    public DecodedMpegAudioInputStream(final AudioFormat outputFormat, final AudioInputStream inputStream) {
        super(outputFormat, -1);
        if (TDebug.TraceAudioConverter) {
            TDebug.out(">DecodedMpegAudioInputStream(AudioFormat outputFormat, AudioInputStream inputStream)");
        }
        try {
            // Try to find out inputstream length to allow skip.
            byteslength = inputStream.available();
        }
        catch (final IOException e) {
            TDebug.out("DecodedMpegAudioInputStream : Cannot run inputStream.available() : " + e.getMessage());
            byteslength = -1;
        }
        m_encodedStream = inputStream;
        shoutlst = IcyListener.getInstance();
        shoutlst.reset();
        m_bitstream = new Bitstream(inputStream);
        m_decoder = new Decoder(null);
        m_equalizer = new Equalizer();
        m_equalizer_values = new float[32];
        for (int b = 0; b < m_equalizer.getBandCount(); b++) {
            m_equalizer_values[b] = m_equalizer.getBand(b);
        }
        m_decoder.setEqualizer(m_equalizer);
        m_oBuffer = new DMAISObuffer(outputFormat.getChannels());
        m_decoder.setOutputBuffer(m_oBuffer);
        try {
            m_header = m_bitstream.readFrame();
            if ((m_header != null) && (frameslength == -1) && (byteslength > 0)) {
                frameslength = m_header.max_number_of_frames((int) byteslength);
            }
        }
        catch (final BitstreamException e) {
            TDebug.out("DecodedMpegAudioInputStream : Cannot read first frame : " + e.getMessage());
            byteslength = -1;
        }
        properties = new HashMap();
    }

    /**
     * Return dynamic properties.
     *
     * <ul>
     * <li><b>mp3.frame</b> [Long], current frame position.
     * <li><b>mp3.frame.bitrate</b> [Integer], bitrate of the current frame.
     * <li><b>mp3.frame.size.bytes</b> [Integer], size in bytes of the current frame.
     * <li><b>mp3.position.byte</b> [Long], current position in bytes in the stream.
     * <li><b>mp3.position.microseconds</b> [Long], elapsed microseconds.
     * <li><b>mp3.equalizer</b> float[32], interactive equalizer array, values could be in [-1.0, +1.0].
     * <li><b>mp3.shoutcast.metadata.key</b> [String], Shoutcast meta key with matching value.
     * <br>For instance :
     * <br>mp3.shoutcast.metadata.StreamTitle=Current song playing in stream.
     * <br>mp3.shoutcast.metadata.StreamUrl=Url info.
     * </ul>
     */
    @Override
    public Map properties() {
        properties.put("mp3.frame", new Long(currentFrame));
        properties.put("mp3.frame.bitrate", new Integer(currentBitrate));
        properties.put("mp3.frame.size.bytes", new Integer(currentFramesize));
        properties.put("mp3.position.byte", new Long(currentByte));
        properties.put("mp3.position.microseconds", new Long(currentMicrosecond));
        properties.put("mp3.equalizer", m_equalizer_values);
        // Optionnal shoutcast stream meta-data.
        if (shoutlst != null) {
            final String surl = shoutlst.getStreamUrl();
            final String stitle = shoutlst.getStreamTitle();
            if ((stitle != null) && (stitle.trim().length() > 0)) {
                properties.put("mp3.shoutcast.metadata.StreamTitle", stitle);
            }
            if ((surl != null) && (surl.trim().length() > 0)) {
                properties.put("mp3.shoutcast.metadata.StreamUrl", surl);
            }
        }
        return properties;
    }

    @Override
    public void execute() {
        if (TDebug.TraceAudioConverter) {
            TDebug.out("execute() : begin");
        }
        try {
            // Following line hangs when FrameSize is available in AudioFormat.
            Header header = null;
            if (m_header == null) {
                header = m_bitstream.readFrame();
            }
            else {
                header = m_header;
            }
            if (TDebug.TraceAudioConverter) {
                TDebug.out("execute() : header = " + header);
            }
            if (header == null) {
                if (TDebug.TraceAudioConverter) {
                    TDebug.out("header is null (end of mpeg stream)");
                }
                getCircularBuffer().close();
                return;
            }
            currentFrame++;
            currentBitrate = header.bitrate_instant();
            currentFramesize = header.calculate_framesize();
            currentByte = currentByte + currentFramesize;
            currentMicrosecond = (long) (currentFrame * header.msPerFrame() * 1000.0f);
            for (int b = 0; b < m_equalizer_values.length; b++) {
                m_equalizer.setBand(b, m_equalizer_values[b]);
            }
            m_decoder.setEqualizer(m_equalizer);
            final Obuffer decoderOutput = m_decoder.decodeFrame(header, m_bitstream);
            m_bitstream.closeFrame();
            getCircularBuffer().write(m_oBuffer.getBuffer(), 0, m_oBuffer.getCurrentBufferSize());
            m_oBuffer.reset();
            if (m_header != null) {
                m_header = null;
            }
        }
        catch (final BitstreamException e) {
            if (TDebug.TraceAudioConverter) {
                TDebug.out(e);
            }
        }
        catch (final DecoderException e) {
            if (TDebug.TraceAudioConverter) {
                TDebug.out(e);
            }
        }
        if (TDebug.TraceAudioConverter) {
            TDebug.out("execute() : end");
        }
    }

    @Override
    public long skip(final long bytes) {
        if ((byteslength > 0) && (frameslength > 0)) {
            final float ratio = bytes * 1.0f / byteslength * 1.0f;
            final long bytesread = skipFrames((long) (ratio * frameslength));
            currentByte = currentByte + bytesread;
            m_header = null;
            return bytesread;
        }
        else {
            return -1;
        }
    }

    /**
     * Skip frames.
     * You don't need to call it severals times, it will exactly skip given frames number.
     *
     * @param frames
     * @return bytes length skipped matching to frames skipped.
     */
    public long skipFrames(final long frames) {
        if (TDebug.TraceAudioConverter) {
            TDebug.out("skip(long frames) : begin");
        }
        int framesRead = 0;
        int bytesReads = 0;
        try {
            for (int i = 0; i < frames; i++) {
                final Header header = m_bitstream.readFrame();
                if (header != null) {
                    final int fsize = header.calculate_framesize();
                    bytesReads = bytesReads + fsize;
                }
                m_bitstream.closeFrame();
                framesRead++;
            }
        }
        catch (final BitstreamException e) {
            if (TDebug.TraceAudioConverter) {
                TDebug.out(e);
            }
        }
        if (TDebug.TraceAudioConverter) {
            TDebug.out("skip(long frames) : end");
        }
        currentFrame = currentFrame + framesRead;
        return bytesReads;
    }

    private boolean isBigEndian() {
        return getFormat().isBigEndian();
    }

    @Override
    public void close()
            throws IOException {
        super.close();
        m_encodedStream.close();
    }

    @Override
    public void tagParsed(final TagParseEvent tpe) {
        System.out.println("TAG:" + tpe.getTag());
    }

    private class DMAISObuffer extends Obuffer {

        private final int m_nChannels;
        private final byte[] m_abBuffer;
        private final int[] m_anBufferPointers;
        private final boolean m_bIsBigEndian;

        public DMAISObuffer(final int nChannels) {
            m_nChannels = nChannels;
            m_abBuffer = new byte[OBUFFERSIZE * nChannels];
            m_anBufferPointers = new int[nChannels];
            reset();
            m_bIsBigEndian = isBigEndian();
        }

        @Override
        public void append(final int nChannel, final short sValue) {
            final byte bFirstByte;
            final byte bSecondByte;
            if (m_bIsBigEndian) {
                bFirstByte = (byte) ((sValue >>> 8) & 0xFF);
                bSecondByte = (byte) (sValue & 0xFF);
            }
            else // little endian
            {
                bFirstByte = (byte) (sValue & 0xFF);
                bSecondByte = (byte) ((sValue >>> 8) & 0xFF);
            }
            m_abBuffer[m_anBufferPointers[nChannel]] = bFirstByte;
            m_abBuffer[m_anBufferPointers[nChannel] + 1] = bSecondByte;
            m_anBufferPointers[nChannel] += m_nChannels * 2;
        }

        @Override
        public void set_stop_flag() {
        }

        @Override
        public void close() {
        }

        @Override
        public void write_buffer(final int nValue) {
        }

        @Override
        public void clear_buffer() {
        }

        public byte[] getBuffer() {
            return m_abBuffer;
        }

        public int getCurrentBufferSize() {
            return m_anBufferPointers[0];
        }

        public void reset() {
            for (int i = 0; i < m_nChannels; i++) {
                /*	Points to byte location,
                 *	implicitely assuming 16 bit
                 *	samples.
                 */
                m_anBufferPointers[i] = i * 2;
            }
        }

    }

}
