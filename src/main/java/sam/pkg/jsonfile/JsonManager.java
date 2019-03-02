package sam.pkg.jsonfile;

import static java.nio.charset.CodingErrorAction.REPORT;
import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;
import static sam.pkg.Utils.SNIPPET_DIR;

import java.io.Closeable;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.AccessController;
import java.security.PrivilegedExceptionAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import sam.console.ANSI;
import sam.io.IOUtils;
import sam.io.fileutils.FilesUtilsIO;
import sam.io.infile.DataMeta;
import sam.io.infile.TextInFile;
import sam.myutils.Checker;
import sam.myutils.MyUtilsPath;
import sam.nopkg.EnsureSingleton;
import sam.nopkg.StringResources;
import sam.pkg.jsonfile.JsonFile.Template;
import sam.string.StringWriter2;

public class JsonManager implements Closeable {
	private static final Logger LOGGER = LoggerFactory.getLogger(JsonManager.class);

	private static final EnsureSingleton singleton = new EnsureSingleton();
	
	private final DataMetas dataMetas;
	private final TextInFile jumbofile;
	private final FileChannel templates;

	private final Path MYDIR;

	private final JsonFile[] files;

	private final int SNIPPET_DIR_COUNT = SNIPPET_DIR.getNameCount();
	private final boolean newFound;

	@SuppressWarnings({ "rawtypes" })
	public JsonManager() throws Exception {
		singleton.init();

		this.MYDIR = AccessController.doPrivileged((PrivilegedExceptionAction<Path>)() -> {
			Path p = MyUtilsPath.TEMP_DIR.resolve(JsonManager.class.getName());
			Files.createDirectories(p);
			return p;
		});
		Path dataMetas_path = MYDIR.resolve("dataMetas");
		Path jumbofile_path = MYDIR.resolve("jumbofile");
		Path templates_path = MYDIR.resolve("templates");
		Path jsonsPath = MYDIR.resolve("jsons");

		if(Checker.anyMatch(Files::notExists, MYDIR, dataMetas_path, jumbofile_path, templates_path, jsonsPath)) {
			FilesUtilsIO.deleteDir(MYDIR);
			Files.createDirectories(MYDIR);
		} 

		JsonManagerUtils utils = new JsonManagerUtils();  

		try(FileChannel jsons = FileChannel.open(jsonsPath, READ,WRITE);
				StringResources r = StringResources.get()) {

			Map<Path, Integer> map;

			if(Files.notExists(dataMetas_path)) {
				this.templates = filechannel(templates_path, true);
				this.dataMetas = new DataMetas(dataMetas_path, true);
				this.jumbofile = new TextInFile(jumbofile_path, true);
				map = Collections.emptyMap();
			} else {
				jsons.position(0);
				map = utils.readJsons(jsons, r);

				this.templates = filechannel(templates_path, false);
				this.dataMetas = new DataMetas(dataMetas_path, false);
				this.jumbofile = new TextInFile(jumbofile_path, false);
			}

			ArrayList<JsonFile> files = new ArrayList<>();  
			List<Path> newJsons = utils.newJsons(files, map, dataMetas, this, r.buffer);

			if(Checker.isNotEmpty(newJsons)) {
				newFound = true;
				utils.handleNewJsons(newJsons, files, jsons, r, this);
			} else
				newFound = false;

			this.files = files.toArray(new JsonFile[files.size()]);
			Arrays.sort(this.files, Comparator.comparingLong(j -> j.lastModified() < 0 ? Long.MAX_VALUE : j.lastModified()));
		}

	}

	private FileChannel filechannel(Path path, boolean create) throws IOException {
		FileChannel file = create ? FileChannel.open(path, READ, WRITE, CREATE_NEW) : FileChannel.open(path, READ, WRITE);
		FileLock lock = file.tryLock();

		if(lock == null) {
			file.close();
			throw new IOException("failed to lock: "+path);
		}
		return file;
	}

	@Override
	public void close() throws IOException {
		JsonMeta[] metas = null;

		if(newFound || Checker.anyMatch(JsonFile::isSaved, files)) {
			metas = new JsonMeta[Arrays.stream(files).mapToInt(f -> f == null ? 0 : f.id).max().orElse(0) + 1];

			for (JsonFile f : files) 
				metas[f.id] = new JsonMeta(f.lastModified(), f.templateMeta);
		}

		try(StringResources r = StringResources.get()) {
			dataMetas.close(metas, r.buffer);
		}

		templates.close();
		jumbofile.close();

		/*
		 * if(Checker.anyMatch(f -> f.isSaved(), files)) {
			try(BufferedWriter w = Files.newBufferedWriter(cacheFilePath(), CREATE, APPEND)) {
				for (JsonFile f : files) {
					if(f.isSaved()) {
						w.append(Integer.toString(f.id)).append('|')
						.append(Long.toString(lastmodified[f.id])).append(' ')
						.append(f.subpath().toString()).append('\n');
					}
				}	
			}
		}
		 */

		//FIXME save modified DateMeta2(s)

		/**
		 * JSON file close
	//TODO @Override
	public void close2() throws IOException {
		if(hasError() || data == null) return;

		if(modified) {
			Path p = cache.keysPath();
			try(OutputStream is = Files.newOutputStream(p, CREATE,TRUNCATE_EXISTING);
					DataOutputStream dos = new DataOutputStream(is);
					) {
				dos.writeInt(data.size());

				for (Template t : data) {
					dos.writeUTF(t.id);
					dos.writeUTF(t.prefix);

					CacheMeta pos = t.dataMeta == null ? CacheMeta.DEFAULT : t.dataMeta;
					dos.writeInt(pos.position());
					dos.writeInt(pos.size());
				}
				//FIXME append instead of truncate
			}
			System.out.println(ANSI.green("saved: ")+p);
		}
	}
		 */
	}

	public List<JsonFile> getFiles() {
		return Collections.unmodifiableList(Arrays.asList(files));
	}

	public StringBuilder loadText(DataMeta dm, StringResources r) throws IOException {
		if(dm == null)
			return null;

		StringBuilder sb = r.sb();
		if(sb.length() != 0)
			throw new IOException("sb.length("+sb.length()+") != 0");

		IOUtils.ensureCleared(r.buffer);
		IOUtils.ensureCleared(r.chars);

		jumbofile.readText(dm, r.buffer, r.chars, r.decoder, sb, REPORT, REPORT);

		r.chars.clear();
		r.buffer.clear();
		return sb;
	}
	
	private boolean preparing_templates;

	public void write(Template t, CharSequence sb, StringResources r) throws IOException {
		IOUtils.ensureCleared(r.buffer);
		DataMeta d = jumbofile.write(sb, r.encoder, r.buffer, REPORT, REPORT);
		
		if(!preparing_templates)
			dataMetas.writeMeta(t.id, d);
		
		t.dataMeta = d;
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	public void transfer(List<DataMeta> metas, WritableByteChannel channel) throws IOException {
		jumbofile.transferTo(metas, channel);
	}
	
	private void saveTemplates(JsonFile json, List<Template> templates2) {
		// TODO Auto-generated method stub
		
	}

	public List<Template> loadTemplates(JsonFile json) throws IOException {
		DataMeta dm = json.templateMeta;

		if(dm == null)
			return parseJson(json);
		else {
			try(StringResources r = StringResources.get()) {
				StringBuilder sb = r.sb();
				ByteBuffer buffer = r.buffer;
				FileChannel file = templates;

				file.position(dm.position);
				IOUtils.read(buffer, true, file);
				int json_id = buffer.getInt();

				if(json_id != json.id)
					LOGGER.warn(": json_id({}) != json.id ({})", json_id, json.id);
				else {
					int size = buffer.getInt();
					List<Template> data = new ArrayList<>(size + 10);

					for (int i = 0; i < size; i++) {
						if(buffer.remaining() < 4) {
							IOUtils.compactOrClear(buffer);
							IOUtils.read(buffer, false, file);
						}
						int id = buffer.getInt();
						String jsonId = JsonManagerUtils.readString(buffer, file, sb);
						String prefix = JsonManagerUtils.readString(buffer, file, sb);

						data.add(json.template(id, jsonId, prefix));
					}
					return data;
				}
			}
			return parseJson(json);
		}
	}

	private List<Template> parseJson(JsonFile json) throws IOException {
		Path p = json.source;
		if(!Files.isRegularFile(p))
			throw new FileNotFoundException("file not found: "+p);
		
		List<Template> templates = new ArrayList<>();
		
		try(StringResources r = StringResources.get()) {
			StringBuilder sb = r.sb();
			StringWriter2 sw = new StringWriter2(sb);
			preparing_templates = true;
			
			//overrided to keep the order of keys;
			new JSONObject(new JSONTokener(Files.newBufferedReader(p, r.CHARSET))) {
				@Override
				public JSONObject put(String key, Object value) throws JSONException {
					super.put(key, value);
					if(has(key)) {
						Template t = json.template(dataMetas.nextId(), key, (JSONObject)value);
						
						try {
							json.writeCache(t, sw, r);
						} catch (IOException e) {
							throw new JSONException(e);
						}
						templates.add(t);
					}
					return this;
				}
			};

			saveTemplates(json,templates);
			dataMetas.writeMetas(templates, r.buffer);
			LOGGER.info("loaded json({}): {}", ANSI.yellow(templates.size()), json);
		} finally {
			preparing_templates = false;
		}
		
		return templates;
	}
	

	public Path subpath(Path source) {
		return source.subpath(SNIPPET_DIR_COUNT, source.getNameCount());
	}
}
