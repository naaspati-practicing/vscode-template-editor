package sam.pkg.jsonfile;

import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import sam.io.IOUtils;
import sam.io.infile.DataMeta;
import sam.io.infile.TextInFile;
import sam.myutils.Checker;
import sam.nopkg.StringResources;
import sam.string.StringSplitIterator;

interface Utils {
	static final Logger logger = LoggerFactory.getLogger(Utils.class);

	public static Iterator<String> readArray(DataMeta d, TextInFile file, StringResources r, char delimeter) throws IOException {
		if(d == null || d.size == 0)
			return null;

		StringBuilder sb = r.sb();
		sb.setLength(0);
		file.readText(d, r.buffer, r.chars, r.decoder, sb);

		if(sb.length() == 0)
			return null;
		return new StringSplitIterator(sb, delimeter);
	}

	public static Map<Integer, Path> jsonPathIdMap(Path snippetDir, TextInFile file, DataMeta meta, StringResources r) throws IOException {
		Iterator<String> array = readArray(meta, file, r, '\t');

		if(array == null)
			return Collections.emptyMap();
		else {
			int n = 0;
			Map<Integer, Path>  map = new HashMap<>();

			while (array.hasNext()) {
				String s = array.next();
				map.put(n++, Checker.isEmptyTrimmed(s) ? null : snippetDir.resolve(s));
			}
			return map;
		}
	}

	static final int IB = Integer.BYTES;
	static final int LB = Long.BYTES;

	static final int DATA_BYTES =
			IB + // id
			LB + // pos
			IB  //size
			;

	public static Iterator<Path> jsonFiles(Path snippetDir) throws IOException {
		return Files.walk(snippetDir)
				.filter(f -> Files.isRegularFile(f) && f.getFileName().toString().toLowerCase().endsWith(".json"))
				.iterator();
	}

	static FileChannel filechannel(Path path, boolean create) throws IOException {
		FileChannel file = create ? FileChannel.open(path, READ, WRITE, CREATE_NEW) : FileChannel.open(path, READ, WRITE);
		FileLock lock = file.tryLock();

		if(lock == null) {
			file.close();
			throw new IOException("failed to lock: "+path);
		}
		return file;
	}

	static interface Consumer {
		void consume(int id, DataMeta meta) throws IOException;
	}

	static void readDataMetasByCount(FileChannel file, long pos, int count, ByteBuffer buf, Consumer consumer) throws IOException {
		int size = count * DATA_BYTES;
		readDataMetasBySize(file, pos, size, buf, consumer);
	}

	public static void readDataMetasBySize(FileChannel file, long pos, int size, ByteBuffer buf, Consumer consumer) throws IOException {
		IOUtils.ensureCleared(buf);
		
		int count = size/DATA_BYTES;
		
		if(count == 0)
			return ;

		if(pos + size > file.size())
			throw new IOException(String.format("pos(%s) + size(%s) = %s > file.size(%s) ", pos, size, pos + size, file.size()));

		for (int i = 0; i < count; i++) {
			buf.limit(Math.min(size, buf.remaining()));
			int n = file.read(buf, pos);
			if(n < 0)
				throw new IOException("not all found, missings: "+(count - i));

			buf.flip();
			while(buf.remaining() >= DATA_BYTES) {
				consumer.consume(buf.getInt(), new DataMeta(buf.getLong(), buf.getInt()));
				size -= DATA_BYTES;
				pos  += DATA_BYTES;
			}
			buf.clear();
		}
	}

	public static void put(ByteBuffer buf, int id, DataMeta d) {
		buf.putInt(id)
		.putLong(d.position)
		.putInt(d.size);
	}
	
	static interface Provider {
		boolean next() throws IOException;
		int id();
		DataMeta dataMeta();
	}
	
	static class WriteMeta {
		final long init_pos;
		long pos;
		int size;
		
		public WriteMeta(long pos) {
			this.init_pos = pos;
			this.pos = pos;
		}

		private void add(int n) {
			pos  += n;
			size += n;
		}

		public void write(ByteBuffer buf, FileChannel fc, boolean flip) throws IOException {
			add(IOUtils.write(buf, pos, fc, flip));
		}
	}

	public static WriteMeta writeDataMetas(long pos, FileChannel fc,  ByteBuffer buffer, Provider provider) throws IOException {
		WriteMeta w = new WriteMeta(pos);
		buffer.clear();
		
		while(provider.next()) {
			if(buffer.remaining() < DATA_BYTES)
				w.write(buffer, fc, true);
			
			put(buffer, provider.id(), Objects.requireNonNull(provider.dataMeta()));
		}
		
		w.write(buffer, fc, true);
		return w;
	}
}
