package sam.pkg.jsonfile.infile;

import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;
import static sam.pkg.jsonfile.infile.Utils.IB;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.BitSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import sam.io.IOUtils;
import sam.io.infile.DataMeta;
import sam.myutils.Checker;
import sam.nopkg.StringResources;

class MetasManager {
	private final Logger logger = LoggerFactory.getLogger(getClass());

	public static final int BYTES = IB + IB; // non-null count, max_non_id
	public static final DataMeta ZERO = new DataMeta(0, 0);

	private final BitSet mod = new BitSet();
	private final DataMeta[] metas;
	private DataMeta[] metas_new = new DataMeta[0];
	private int _nextId = 0;
	final Path path; 

	public MetasManager(Path path) {
		metas = new DataMeta[0];
		this.path = path;
	}

	public MetasManager(Path path, ByteBuffer buffer) throws IOException {
		this.path = path;

		try(FileChannel fc = FileChannel.open(path, READ)) {
			buffer.clear();

			fc.read(buffer);
			buffer.flip();
			metas = new DataMeta[buffer.getInt()];

			while(true) {
				if(buffer.remaining() < BYTES) {
					IOUtils.compactOrClear(buffer);
					if(fc.read(buffer) < 0)
						break;
					buffer.flip();
				}
				
				int id = buffer.getInt(); 
				long pos = buffer.getLong();
				int size = buffer.getInt();
				
				metas[id] = pos == 0 && size == 0 ? ZERO : new DataMeta(pos, size);
			}
		}
	}

	public void close(StringResources r) throws Exception {
		if(mod.isEmpty())
			return;
		
		int length = Math.max(_nextId + 1, metas.length);
		boolean truncate = mod.cardinality() > length/2;

		try(FileChannel fc = FileChannel.open(path, WRITE, CREATE, truncate ? TRUNCATE_EXISTING : APPEND)) {
			ByteBuffer buffer = r.buffer;
			buffer.clear();
			
			buffer.putInt(length);
			
			if(!truncate) {
				buffer.flip();
				fc.write(buffer, 0);
				buffer.clear();
			}
			
			logger.debug(truncate ? "TRUNCATE" : "APPEND");
			
			int n = write(metas, fc, buffer, 0);
			Checker.assertTrue(n == metas.length);
			write(metas_new, fc, buffer, n);

			IOUtils.write(buffer, fc, true);
		}
	}

	private int write(DataMeta[] metas, FileChannel fc, ByteBuffer buffer, int id) throws IOException {
		if(Checker.isEmpty(metas))
			return id;

		for (DataMeta d : metas) {
			if(buffer.remaining() < BYTES)
				IOUtils.write(buffer, fc, true);
			
			int n = id++;
			if(mod.get(n)) {
				d = d == null ? ZERO : d;
				
				buffer.putInt(n)
				.putLong(d.position)
				.putInt(d.size);
			}
		}
		return id;
	}

	public void set(int id, DataMeta d) {
		if(id <  0 || (id > _nextId && id > metas.length))
			throw new IllegalArgumentException(String.format("id out of bounds: id(%s) > _nextId(%s) && id(%s) > metas.length(%s)", id, _nextId, id, metas.length));
		
		mod.set(id);
		
		if(id < metas.length)
			metas[id] = d;
		else {
			if(id - metas.length >= metas_new.length) {
				DataMeta[] ds = new DataMeta[Math.max(metas_new.length * metas_new.length/4, 20)];
				System.arraycopy(metas_new, 0, ds, 0, metas_new.length);

				logger.debug("templates_new.length({} -> {})", metas_new.length, ds.length);
				metas_new = ds;
			}

			metas_new[id - metas.length] = d;
		}

		logger.debug("set DataMeta -> id: {}, meta: {}", id, d);
	}

	private DataMeta meta(int id) {
		if(id < metas.length)
			return metas[id];
		else if(id - metas.length < metas_new.length)
			return metas_new[id - metas.length];
		else
			return null;
	}

	public int nextId() {
		while(meta(_nextId++) != null) {}
		return _nextId - 1; 
	}
}
