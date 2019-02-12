package sam.pkg.jsonfile;

import static java.nio.charset.CodingErrorAction.REPORT;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;

import sam.string.StringWriter2;

public class Resource {
	private Charset CHARSET = StandardCharsets.UTF_8;
	private ByteBuffer buffer = ByteBuffer.allocate(1024*8);
	private CharBuffer chars = CharBuffer.allocate(1024);
	private CharsetEncoder encoder;
	private CharsetDecoder decoder;

	private final StringWriter2 tw = new StringWriter2();
	private static volatile Resource INSTANCE;

	public static Resource getInstance() {
		if (INSTANCE != null)
			return INSTANCE;

		synchronized (Resource.class) {
			if (INSTANCE != null)
				return INSTANCE;

			INSTANCE = new Resource();
			return INSTANCE;
		}
	}

	private Resource() { }
	
	public CharBuffer charBuffer() {
		if(chars.remaining() != chars.capacity())
			throw new IllegalStateException("("+chars.remaining()+") "+chars);
		return chars;
	}
	public StringWriter2 templateWriter() {
		if(tw.getBuilder().length() != 0)
			throw new IllegalStateException(String.valueOf(tw.getBuilder().length()));
		return tw;
	}
	public ByteBuffer buffer(int len) {
		if(buffer.remaining() != buffer.capacity())
			throw new IllegalStateException(String.valueOf(buffer.remaining()));
		
		if(len < 0)
			return buffer;
		
		if(buffer.capacity() < len) {
			buffer = ByteBuffer.allocate(len+10);
			System.out.println(getClass().getSimpleName()+": new buffer created of length: "+(len+10));
		}
		return buffer;
	}

	public CharsetDecoder decoder() {
		if(decoder == null)
			decoder = CHARSET.newDecoder().onMalformedInput(REPORT).onUnmappableCharacter(REPORT);
		else 
			decoder.reset();
		
		return  decoder;
	}

	public Charset charset() {
		return CHARSET;
	}

	public CharsetEncoder encoder() {
		if(encoder == null)
			encoder = CHARSET.newEncoder().onMalformedInput(REPORT).onUnmappableCharacter(REPORT);
		else
			encoder.reset();
		return encoder;
	}

}
