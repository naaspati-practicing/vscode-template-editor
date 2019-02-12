package sam.pkg.jsonfile;

import static sam.pkg.Utils.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

class LoadedFile {
	private final ByteBuffer buffer = ByteBuffer.allocate(8 * 1024);
	private Path loaded;
	private int limit;
	private int position;

	public ByteBuffer read(CacheMeta range, Path path) throws IOException {
		int pos = range.position() - this.position;
		int lim = pos + range.size();
		
		if(!path.equals(loaded) || pos < 0 || lim > this.limit)
			return read(path, pos, range.size());

		buffer.position(pos);
		buffer.limit(lim);
		return buffer.asReadOnlyBuffer();
	}

	private ByteBuffer read(Path path, final int position, final int size) throws IOException {
		try(FileChannel file = FileChannel.open(path, StandardOpenOption.READ);) {
			long filesize = file.size();
			
			if(position + size > filesize)
				throw new IOException(String.format("out of bounds: position(%d) + size(%d) > filesize(%d), for file: %s", position, size, filesize, subpathWithPrefix(path)));
			
			ByteBuffer buffer  = this.buffer; 
			if(position != 0)
				file.position(position);
			
			if(buffer.capacity() < size) {
				buffer = ByteBuffer.allocate(size);
				System.out.println(getClass().getSimpleName()+": buffer created("+buffer.capacity()+")");
			} else {
				buffer.clear();
				this.loaded = path;
			}

			while(file.read(buffer) != -1 && buffer.hasRemaining()) { }
			
			buffer.flip();
			if(buffer == this.buffer) {
				this.position = position;
				this.limit = buffer.limit();
				System.out.println("loaded: "+subpathWithPrefix(path));
			} else 
				System.out.println("read into temp buffer: "+subpathWithPrefix(path));
			
			buffer.limit(position + size);
			return buffer.asReadOnlyBuffer();
		}
	}
}