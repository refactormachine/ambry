package com.github.ambry.router;

import com.github.ambry.network.ReadableStreamChannel;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.WritableByteChannel;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;


/**
 * Tests functionality of {@link ReadableStreamChannelInputStream}.
 */
public class ReadableStreamChannelInputStreamTest {

  @Test
  public void commonCaseTest()
      throws IOException {
    byte[] in = new byte[1024];
    new Random().nextBytes(in);
    readByteByByteTest(in);
    readPartByPartTest(in);
    readAllAtOnceTest(in);
  }

  @Test
  public void readErrorCasesTest()
      throws IOException {
    byte[] in = new byte[1024];
    new Random().nextBytes(in);
    InputStream srcInputStream = new ByteArrayInputStream(in);
    ReadableStreamChannel channel = new DataStreamChannel(srcInputStream, in.length);
    InputStream dstInputStream = new ReadableStreamChannelInputStream(channel);
    try {
      dstInputStream.read(null, 0, in.length);
    } catch (NullPointerException e) {
      // expected. nothing to do.
    }

    byte[] out = new byte[in.length];
    try {
      dstInputStream.read(out, -1, out.length);
    } catch (IndexOutOfBoundsException e) {
      // expected. nothing to do.
    }

    try {
      dstInputStream.read(out, 0, -1);
    } catch (IndexOutOfBoundsException e) {
      // expected. nothing to do.
    }

    try {
      dstInputStream.read(out, 0, out.length + 1);
    } catch (IndexOutOfBoundsException e) {
      // expected. nothing to do.
    }

    assertEquals("Bytes read should have been 0 because passed len was 0", 0, dstInputStream.read(out, 0, 0));
  }

  @Test
  public void nonBlockingToBlockingTest()
      throws IOException {
    byte[] in = new byte[1024];
    new Random().nextBytes(in);
    ReadableStreamChannel channel = new HaltingReadableStreamChannel(ByteBuffer.wrap(in), 5);
    InputStream dstInputStream = new ReadableStreamChannelInputStream(channel);
    byte[] out = new byte[in.length];
    // The HaltingReadableStreamChannel returns 0 bytes read 5 times but that should not change anything for us.
    assertEquals("Bytes read did not match size of source array", in.length, dstInputStream.read(out));
    assertArrayEquals("Byte array obtained from InputStream did not match source", in, out);
    assertEquals("Did not receive expected EOF", -1, dstInputStream.read(out));
  }

  // commonCaseTest() helpers
  private void readByteByByteTest(byte[] in)
      throws IOException {
    InputStream srcInputStream = new ByteArrayInputStream(in);
    ReadableStreamChannel channel = new DataStreamChannel(srcInputStream, in.length);
    InputStream dstInputStream = new ReadableStreamChannelInputStream(channel);
    for (int i = 0; i < in.length; i++) {
      assertEquals("Byte [" + i + "] does not match expected", in[i], (byte) dstInputStream.read());
    }
    assertEquals("Did not receive expected EOF", -1, dstInputStream.read());
  }

  private void readPartByPartTest(byte[] in)
      throws IOException {
    InputStream srcInputStream = new ByteArrayInputStream(in);
    ReadableStreamChannel channel = new DataStreamChannel(srcInputStream, in.length);
    InputStream dstInputStream = new ReadableStreamChannelInputStream(channel);
    byte[] out = new byte[in.length];
    for (int start = 0; start < in.length; ) {
      int end = Math.min(start + in.length / 4, in.length);
      int len = end - start;
      assertEquals("Bytes read did not match what was requested", len, dstInputStream.read(out, start, len));
      assertArrayEquals("Byte array obtained from InputStream did not match source", Arrays.copyOfRange(in, start, end),
          Arrays.copyOfRange(out, start, end));
      start = end;
    }
    assertEquals("Did not receive expected EOF", -1, dstInputStream.read(out, 0, out.length));
  }

  private void readAllAtOnceTest(byte[] in)
      throws IOException {
    InputStream srcInputStream = new ByteArrayInputStream(in);
    ReadableStreamChannel channel = new DataStreamChannel(srcInputStream, in.length);
    InputStream dstInputStream = new ReadableStreamChannelInputStream(channel);
    byte[] out = new byte[in.length];
    assertEquals("Bytes read did not match size of source array", in.length, dstInputStream.read(out));
    assertArrayEquals("Byte array obtained from InputStream did not match source", in, out);
    assertEquals("Did not receive expected EOF", -1, dstInputStream.read(out));
  }
}

/**
 * Class that returns 0 bytes on read a fixed number of times.
 */
class HaltingReadableStreamChannel implements ReadableStreamChannel {
  private final AtomicBoolean channelOpen = new AtomicBoolean(true);
  private final AtomicInteger haltTimes;
  private final ByteBuffer data;

  public HaltingReadableStreamChannel(ByteBuffer data, int haltTimes) {
    this.data = data;
    this.haltTimes = new AtomicInteger(haltTimes);
  }

  @Override
  public long getSize() {
    return data.limit();
  }

  @Override
  public int read(WritableByteChannel channel)
      throws IOException {
    int bytesWritten;
    if (!channelOpen.get()) {
      throw new ClosedChannelException();
    } else if (haltTimes.getAndDecrement() > 0) {
      bytesWritten = 0;
    } else if (!data.hasRemaining()) {
      bytesWritten = -1;
    } else {
      bytesWritten = channel.write(data);
    }
    return bytesWritten;
  }

  @Override
  public void close()
      throws IOException {
    channelOpen.set(false);
  }
}