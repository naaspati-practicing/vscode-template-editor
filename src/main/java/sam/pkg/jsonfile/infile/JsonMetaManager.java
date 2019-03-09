package sam.pkg.jsonfile.infile;
import static java.nio.charset.CodingErrorAction.REPORT;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;
import static java.util.Arrays.sort;
import static java.util.Arrays.stream;
import static java.util.Comparator.comparingLong;
import static org.junit.jupiter.api.Assertions.assertNull;
import static sam.io.IOUtils.compactOrClear;
import static sam.myutils.Checker.assertTrue;
import static sam.myutils.Checker.isEmptyTrimmed;
import static sam.myutils.Checker.isNotEmpty;
import static sam.pkg.jsonfile.infile.Utils.IB;
import static sam.pkg.jsonfile.infile.Utils.LB;
import static sam.pkg.jsonfile.infile.Utils.ZERO;
import static sam.pkg.jsonfile.infile.Utils.putMeta;
import static sam.pkg.jsonfile.infile.Utils.readMeta;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import sam.io.BufferConsumer;
import sam.io.BufferSupplier;
import sam.io.IOUtils;
import sam.io.infile.DataMeta;
import sam.io.serilizers.StringIOUtils;
import sam.myutils.Checker;
import sam.nopkg.StringResources;
import sam.pkg.jsonfile.api.JsonManager;
import sam.string.StringSplitIterator;

class JsonMetaManager implements Closeable {
	private final Logger logger = LoggerFactory.getLogger(getClass());

	public class JsonMeta {
		protected static final int BYTES =
				IB + // id
				LB + // lasmodified
				DataMeta.BYTES * 2 ;

		final int id;
		final JsonFileImpl jsonfile;
		private long lastmodified;
		private DataMeta names_meta, data_meta; // location of templates string, in smallfile

		private JsonMeta(ByteBuffer buffer, Path path) {
			this.id = buffer.getInt();
			this.lastmodified = buffer.getLong();
			this.names_meta = readMeta(buffer);
			this.data_meta = readMeta(buffer);
			
			this.jsonfile = id == -1 ? null : new JsonFileImpl(id, path, loader);
		}

		private JsonMeta(int id, Path file) {
			this.id = id;
			this.lastmodified = file.toFile().lastModified();
			this.jsonfile = new JsonFileImpl(id, file, loader);
		}
		public long lastmodified() {
			return lastmodified;
		}

		public DataMeta namesMeta() {
			return names_meta;
		}
		public DataMeta datametaMeta() {
			return data_meta;
		}

		public void datametaMeta(DataMeta data_meta) {
			mod++;
			this.data_meta = data_meta;
		}
		public void namesMeta(DataMeta names_meta) {
			mod++;
			this.names_meta = names_meta;
		}
	}

	public static void putJsonMeta(JsonMeta j, ByteBuffer buffer) {
		if(j == null) {
			buffer
			.putInt(-1)
			.putLong(-1);

			putMeta(ZERO, buffer);
			putMeta(ZERO, buffer);
		} else {
			buffer
			.putInt(j.id)
			.putLong(j.lastmodified());

			putMeta(j.names_meta, buffer);
			putMeta(j.data_meta, buffer);
		}
	}

	private JsonMeta[] jsonMetas;
	private final Path path, snippetDir;
	private int mod;
	private final JsonLoader loader;

	public JsonMeta jsonMeta(JsonFileImpl json) {
		return jsonMetas[json.id];
	}

	private JsonMeta[] loadAll(StringResources r, FileChannel file) throws IOException {
		Checker.assertIsNull(jsonMetas);

		Path[] p = JsonManager.walk(snippetDir).toArray(Path[]::new);
		this.jsonMetas = new JsonMeta[p.length];

		for (int i = 0; i < p.length; i++)  
			jsonMetas[i] = new JsonMeta(i, p[i]);

		logger.debug("NEW JSON FOUND: "+jsonMetas.length);
		writeNewCopy(r, file);
		return jsonMetas;
	}

	private void writeNewCopy(StringResources r, FileChannel file0) throws IOException {
		try(FileChannel file = file0 != null ? file0 : FileChannel.open(path, CREATE, WRITE);) {

			file.truncate(0);
			ByteBuffer buffer = r.buffer;
			buffer.clear();

			buffer
			.putInt(0)
			.putInt(0);

			StringBuilder sb = r.sb();
			sb.setLength(0);

			for (JsonMeta s : jsonMetas)
				sb.append(s == null ? "" : s.jsonfile.source).append('\t');

			StringIOUtils.write(BufferConsumer.of(file, false), sb, r.encoder, buffer, REPORT, REPORT);
			sb.setLength(0);

			buffer.clear();
			buffer
			.putInt((int) file.position())
			.putInt(jsonMetas.length)
			.flip();

			file.write(buffer, 0);

			writeMetas(file, buffer);
		}
	}

	private void writeMetas(FileChannel file, ByteBuffer buffer) throws IOException {
		buffer.clear();
		buffer.limit(IB);
		file.read(buffer, 0);

		int pos = buffer.getInt();
		file.truncate(pos);
		file.position(pos);

		buffer.clear();

		for (JsonMeta j : jsonMetas) {
			if(buffer.remaining() < JsonMeta.BYTES) 
				IOUtils.write(buffer, file, true);
			putJsonMeta(j, buffer);
		}

		IOUtils.write(buffer, file, true);
		mod = 0;
	}

	public JsonMetaManager(Path path, StringResources r, Path snippetDir, JsonLoader loader) throws IOException {
		this.loader = loader;
		this.snippetDir = snippetDir;
		this.path = path;

		if(Files.notExists(path)) {
			loadAll(r, null);
			return;
		}

		try(FileChannel file = FileChannel.open(path, READ)) {
			final ByteBuffer buffer = r.buffer;
			buffer.clear();

			int n = file.read(buffer); 
			if(n < IB) {
				loadAll(r, file);
				return;
			} 

			buffer.flip();

			final int init_size = buffer.getInt();
			final int count = buffer.getInt();
			buffer.limit(Math.min(buffer.limit(), init_size));

			StringBuilder sb = r.sb();
			sb.setLength(0);

			StringIOUtils.read(new BufferSupplier() {
				int size = init_size - buffer.limit();
				boolean first;

				@Override
				public ByteBuffer next() throws IOException {
					if(first)
						return buffer;

					if(isEndOfInput())
						return null;

					compactOrClear(buffer);
					buffer.limit(buffer.position() + Math.min(size, buffer.remaining()));
					int n = file.read(buffer);

					if(n < 0) 
						throw new IOException("not all bytes found: total: "+init_size+", remaining: "+size);

					size -= n;
					return null;
				}
				@Override
				public boolean isEndOfInput() throws IOException {
					return init_size <= 0;
				}
			}, sb, r.decoder, r.chars, REPORT, REPORT);

			if(sb.length() == 0) {
				loadAll(r, file);
				return;
			}

			Checker.assertTrue(file.position() == init_size);

			JsonMeta[] metas = new JsonMeta[count];
			final Path[] paths = new Path[count];

			Iterator<String> array = new StringSplitIterator(sb, '\t');
			n = 0;

			while (array.hasNext()) {
				String s = array.next();
				paths[n++] = isEmptyTrimmed(s) ? null : snippetDir.resolve(s);  
			}

			assertTrue(n == metas.length);

			if(paths.length == 0 || Checker.allMatch(Checker::notExists, paths)) {
				loadAll(r, file);
				return;
			} 

			buffer.clear();
			file.read(buffer);
			buffer.flip();

			for (int i = 0; i < metas.length; i++) {
				if(buffer.remaining() < JsonMeta.BYTES) {
					compactOrClear(buffer);
					file.read(buffer);
					buffer.flip();
				}
				JsonMeta m = new JsonMeta(buffer, paths[i]);
				
				if(m.jsonfile != null) 
					metas[i] = m;
			}

			Map<Path, Integer> map = new HashMap<>();
			
			for (int i = 0; i < paths.length; i++) {
				if(paths[i] != null && metas[i] != null)
					map.put(paths[i], i);
			}

			Iterator<Path> json_files = JsonManager.walk(snippetDir).iterator();
			List<Path> newJsons = new ArrayList<>();
			BitSet found = new BitSet();
			
			final int snipCount = snippetDir.getNameCount();

			while (json_files.hasNext()) {
				Path f = json_files.next();
				Integer index = map.get(f);
				JsonMeta t = index == null ? null : metas[index];
				
				if(t != null && t.lastmodified != f.toFile().lastModified()) {
					logger.warn("reset JsonFileImpl: lastmodified({} -> {}), path: \"{}\"", t.lastmodified, f.toFile().lastModified(), f.subpath(snipCount, f.getNameCount()));
					metas[t.id] = null;
					t = null;
				}

				if(t == null) 
					newJsons.add(f);
				else 
					found.set(t.id);
			}
			
			for (int i = 0; i < metas.length; i++) {
				if(!found.get(i))
					metas[i] = null;
			}

			if(isNotEmpty(newJsons)) {
				n = metas.length + newJsons.size();
				metas = n > metas.length ? Arrays.copyOf(metas, n) : metas;
				
				n = 0;

				for (Path p : newJsons) {
					while(metas[n] != null) {n++;}
					assertNull(metas[n]);

					String subpath = p.subpath(snipCount, p.getNameCount()).toString();

					metas[n] = new JsonMeta(n, p); 
					logger.info("new JsonFileImpl id: {}: %SNIPPET_DIR%\\{}", n, subpath);
				}

				int max = Arrays.stream(metas).filter(t -> t != null).mapToInt(t -> t.id).max().getAsInt();
				if(max + 1 != metas.length)
					metas = Arrays.copyOf(metas, max + 1);
				
				writeNewCopy(r, file);
			}
			
			for (int i = 0; i < metas.length; i++) 
				assertTrue(metas[i] == null || metas[i].id == i);
			
			this.jsonMetas = metas;
		}
	}

	@Override
	public void close() throws IOException {
		if(mod != 0) {
			try(FileChannel fc = FileChannel.open(path);
					StringResources r = StringResources.get()) {
				writeMetas(fc, r.buffer);	
			}
		}
	}

	public JsonFileImpl[] toFiles() {
		JsonFileImpl[] files = stream(jsonMetas).filter(Objects::nonNull).map(m -> m.jsonfile).toArray(JsonFileImpl[]::new);
		sort(files, comparingLong(j -> jsonMetas[j.id].lastmodified() < 0 ? Long.MAX_VALUE : jsonMetas[j.id].lastmodified()));
		return files;
	}
}

