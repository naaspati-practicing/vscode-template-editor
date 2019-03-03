package sam.pkg.jsonfile;

import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import sam.io.IOUtils;
import sam.io.infile.DataMeta;
import sam.myutils.Checker;
import sam.pkg.jsonfile.JsonFile.Template;

class DataMetas {
	private static final Logger LOGGER = LoggerFactory.getLogger(DataMetas.class);
	private static final boolean DEBUG_ENABLED = LOGGER.isDebugEnabled();

	public static final int JSON_FILES_PATHS = 0x27;
	public static final int JSON_FILES_META = 0x29;
	private static final int MY_META = 0x8282;
	

	private static final int LONG_B = Long.BYTES;
	private static final int INT_B = Integer.BYTES;

	private static final int DATA_META_BYTES =
			INT_B // id 
			+ LONG_B // templates-pos
			+ INT_B; // templates-size

	private static final int RESERVED_IDS = 10;
	private static final long META_RESERVED  = DATA_META_BYTES * (RESERVED_IDS + 1);

	private final ByteBuffer buffer = ByteBuffer.allocate(JSON_BYTES);

	private final FileChannel file;
	private final FileLock lock;
	private int nextId;
	private int jsonArraySize;

	public DataMetas(Path path, boolean create) throws IOException {
		this.file = create ? FileChannel.open(path, READ, WRITE, CREATE_NEW) : FileChannel.open(path, READ, WRITE);
		this.lock = file.tryLock();
		if(lock == null) {
			file.close();
			throw new IOException("failed to lock: "+path);
		}
		DataMeta meta = get_reserved(MY_META);

		if(meta != null) {
			nextId = (int) meta.position;
			jsonArraySize = meta.size;
		}
	}
	
	private long pos_reserved(int type) throws IOException {
		switch (type) {
			case MY_META: return 0;
			case JSON_FILES_PATHS: return DATA_META_BYTES;
			case JSON_FILES_META: return DATA_META_BYTES * 2;
			default: throw new IOException("unknown type: "+type);
		}
	}

	public DataMeta get_reserved(int type) throws IOException {
		if(read(pos_reserved(type), DATA_META_BYTES) < DATA_META_BYTES)
			return null;
		
		if(buffer.getInt() != type) {
			LOGGER.error("buffer.getInt({}) != type({})", buffer.getInt(0), type);
			return null;
		}
		
		return new DataMeta(buffer.getLong(), buffer.getInt());
	}

	public void write_reserved(int type, DataMeta dm) throws IOException {
		Objects.requireNonNull(dm);
		write(type, dm, pos_reserved(type));
	}

	private void write(int id, DataMeta dm, long pos) throws IOException {
		buffer.clear();
		buffer.putInt(id)
		.putLong(dm.position)
		.putInt(dm.size)
		.flip();
		
		file.write(buffer, pos);
	}

	private int read(long position, int limit) throws IOException {
		buffer.clear();
		buffer.limit(limit);
		int n = file.read(buffer, position);
		buffer.flip();
		return n;
	}

	public void close(JsonMeta[] metas, ByteBuffer buffer) throws IOException {
		write_reserved(MY_META, new DataMeta(nextId, metas == null ? 0 : metas.length));

		boolean written = false;
		if(Checker.isNotEmpty(metas)) {
			for (int i = 0; i < metas.length; i++) {
				JsonMeta m = metas[i];

				if(buffer.remaining() < JSON_BYTES) {
					IOUtils.write(buffer, file, true);
					written = true;
				}

				boolean b = m == null;

				buffer
				.putInt(b ? -1 : i)
				.putLong(b ? -1 : m.lastmodified)
				.putLong(b ? -1 : m.templateMeta.position)
				.putInt(b ? -1 : m.templateMeta.size);

				written = false;
			}
		}

		if(!written)
			IOUtils.write(buffer, file, true);

		file.close();
		lock.release();
	}

	public static final int JSON_BYTES = 
			INT_B // json_id 
			+ LONG_B // lastModified
			+ LONG_B // templates-pos
			+ INT_B; // templates-size

	private static final int JSON_MAX_ID = 200;
	private static final long JSON_MIN_POS = META_RESERVED * 2;
	private static final long JSON_MAX_POS = JSON_MIN_POS + JSON_MAX_ID * JSON_BYTES;

	public JsonMeta[] jsonMetas(ByteBuffer buffer) throws IOException {
		JsonMeta[] array = new JsonMeta[jsonArraySize];
		if(array.length == 0)
			return array;

		buffer.clear();
		long pos = JSON_MIN_POS;
		int n = file.read(buffer, pos);
		if(n < JSON_BYTES)
			return array;

		buffer.flip();

		for (int i = 0; i < array.length; i++) {
			if(buffer.remaining() < JSON_BYTES) {
				buffer.clear();
				if(file.read(buffer, pos) < -1)
					return array;
			}

			int id = buffer.getInt();
			long lastModified = buffer.getLong();
			long dpos = buffer.getLong();
			int dsize = buffer.getInt();

			pos += JSON_BYTES;

			if(id == i)
				array[i] = new JsonMeta(lastModified, new DataMeta(dpos, dsize));
			else if(id != -1)
				LOGGER.warn("id({}) != {}", id, i);
		}

		if(DEBUG_ENABLED)
			LOGGER.debug(Arrays.toString(array));

		return array;
	}

	private static final int MAX_ID = 5000;
	private static final long ID_MIN_POS = JSON_MAX_POS + JSON_BYTES * 2;
	private static final long ID_MAX_POS = ID_MIN_POS + MAX_ID * DATA_META_BYTES;

	private long pos(int id) throws IOException {
		if(id < 0)
			throw new IOException("id("+id+") < 0");
		if(id > MAX_ID)
			throw new IOException("id("+id+") > "+MAX_ID);

		long pos = (id + 1) * DATA_META_BYTES + ID_MIN_POS;
		if(pos > ID_MAX_POS)
			throw new IOException("pos("+pos+") > ID_MAX_POS"+ID_MAX_POS);

		return pos;
	}
	public DataMeta readMeta(int id) throws IOException {
		long pos = pos(id);

		if(read(pos, DATA_META_BYTES) < DATA_META_BYTES || buffer.getInt() != id)
			return null;

		DataMeta d = new DataMeta(buffer.getLong(), buffer.getInt());

		if(DEBUG_ENABLED)
			LOGGER.debug("READ: id: {}, dataMeta: {}, pos: {}", id, d, pos);

		return d;
	}

	public void writeMeta(int id, DataMeta d) throws IOException {
		buffer.clear();
		put(buffer, id, d).flip();

		long pos = pos(id);
		file.write(buffer, pos);

		if(DEBUG_ENABLED)
			LOGGER.debug("written: id: {}, dataMeta: {}, pos: {}", id, d, pos);
	}

	private static ByteBuffer put(ByteBuffer buffer, int id, DataMeta d) {
		return buffer.putInt(id)
				.putLong(d.position)
				.putInt(d.size);
	}

	public int nextId() {
		return nextId++;
	}

	public void writeMetas(List<Template> templates, ByteBuffer buffer) throws IOException {
		IOUtils.ensureCleared(buffer);

		Template t1 = templates.get(0);
		put(buffer, t1.id, t1.dataMeta);

		for (int i = 1; i < templates.size(); i++) {
			Template t = templates.get(i);

			if(buffer.remaining() < DATA_META_BYTES || t.id != t1.id + 1) {
				if(DEBUG_ENABLED)
					LOGGER.debug("write: {} -> {}", t1.id, t.id);

				buffer.flip();
				file.write(buffer, pos(t1.id));
			}

			put(buffer, t.id, t.dataMeta);
			t1 = t;
		}

		buffer.flip();
		file.write(buffer, pos(t1.id));

		if(DEBUG_ENABLED)
			LOGGER.debug("write: {} -> {}", t1.id, templates.get(templates.size() - 1).id);
	}


}

