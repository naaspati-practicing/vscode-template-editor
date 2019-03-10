package sam.pkg.jsonfile.infile;

import static java.nio.charset.CodingErrorAction.REPORT;
import static java.util.Arrays.asList;
import static java.util.Collections.unmodifiableList;
import static sam.io.IOUtils.ensureCleared;
import static sam.myutils.Checker.anyMatch;
import static sam.pkg.jsonfile.infile.Utils.putMeta;
import static sam.pkg.jsonfile.infile.Utils.readMeta;

import java.io.Closeable;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.AccessController;
import java.security.PrivilegedExceptionAction;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import sam.console.ANSI;
import sam.io.infile.DataMeta;
import sam.io.infile.TextInFile;
import sam.myutils.MyUtilsException;
import sam.myutils.MyUtilsPath;
import sam.nopkg.StringResources;
import sam.pkg.jsonfile.api.JsonFile;
import sam.pkg.jsonfile.api.JsonManager;
import sam.pkg.jsonfile.infile.JsonFileImpl.TemplateImpl;
import sam.pkg.jsonfile.infile.JsonMetaManager.JsonMeta;
import sam.string.StringSplitIterator;
import sam.string.StringWriter2;

public class JsonManagerImpl implements Closeable, JsonManager, JsonLoader {
	private final Logger logger = LoggerFactory.getLogger(getClass());

	/*
	 * contains Json text
	 */
	private final TextInFile jumbofile;
	/*
	 * contains JsonFileImpl, templates
	 */
	private final TextInFile smallfile;

	final Path MYDIR;
	private final List<JsonFile> files;
	private BitSet jsonMetaMod = new BitSet();
	private final int SNIPPET_DIR_COUNT;
	private final JsonMetaManager jsonMetaManager;

	public JsonManagerImpl(Path snippetDir) throws Exception {
		Objects.requireNonNull(snippetDir);
		this.SNIPPET_DIR_COUNT = snippetDir.getNameCount();
		if(!Files.isDirectory(snippetDir))
			throw new FileNotFoundException("snippet dir not found: "+snippetDir);

		this.MYDIR = AccessController.doPrivileged((PrivilegedExceptionAction<Path>)() -> {
			Path p = MyUtilsPath.TEMP_DIR.resolve(JsonManagerImpl.class.getName());
			Files.createDirectories(p);
			return p;
		});
		
		System.out.println(MYDIR);

		Path jumbofile_path = resolve("jumbofile");
		Path smallfile_path = resolve("smallfile");
		Path jsonfilesmeta_path = resolve("jsonfilesmeta");

		boolean fresh = false;

		if(Files.isDirectory(MYDIR)) {
			Path[] array = {jumbofile_path, smallfile_path, jsonfilesmeta_path};
			if(anyMatch(Files::notExists, array)) {
				for (Path p : array) 
					Files.deleteIfExists(p);

				fresh = true;
			}
		} else {
			fresh = true;
		}

		try(StringResources r = StringResources.get()) {
			this.jsonMetaManager = new JsonMetaManager(jsonfilesmeta_path, r, snippetDir, this);
		}

		if(fresh) {
			Files.createDirectories(MYDIR);
			this.smallfile = new TextInFile(smallfile_path, true);
			this.jumbofile = new TextInFile(jumbofile_path, true);
		} else {
			this.smallfile = new TextInFile(smallfile_path, false);
			this.jumbofile = new TextInFile(jumbofile_path, false);
		}

		this.files = unmodifiableList(asList(jsonMetaManager.toFiles()));
	}

	private void closeSilently(AutoCloseable file, String tag) {
		if(file != null)
			MyUtilsException.hideError(() -> file.close(), e -> logger.error("failed to close "+tag, e));
	}

	private Path resolve(String s) {
		return MYDIR.resolve(s);
	}

	public void close() throws IOException {
		closeSilently(smallfile, "smallfile");
		closeSilently(jumbofile, "jumbofile");
		
		jsonMetaManager.close();
	}

	@Override
	public List<JsonFile> getFiles() {
		return files;
	}
	
	public StringBuilder loadText(TemplateImpl tm, StringResources r) throws IOException {
		return loadText(tm.getMeta(), r);
	}
	private  StringBuilder loadText(DataMeta dm, StringResources r) throws IOException {
		if(dm == null)
			return null;

		StringBuilder sb = r.sb();
		if(sb.length() != 0)
			throw new IOException("sb.length("+sb.length()+") != 0");

		ensureCleared(r.buffer);
		ensureCleared(r.chars);

		jumbofile.readText(dm, r.buffer, r.chars, r.decoder, sb, REPORT, REPORT);

		r.chars.clear();
		r.buffer.clear();
		return sb;
	}
	
	private boolean  batchWrite;

	public void write(JsonFileImpl json, TemplateImpl t, CharSequence sb, StringResources r) throws IOException {
		ensureCleared(r.buffer);
		t.setMeta(jumbofile.write(sb, r.encoder, r.buffer, REPORT, REPORT));
		r.buffer.clear();
		
		if(!batchWrite)
			jsonMetaMod.set(json.id);
	}

	public void transfer(List<DataMeta> metas, WritableByteChannel channel) throws IOException {
		jumbofile.transferTo(metas, channel);
	}

	public List<TemplateImpl> loadTemplates(JsonFileImpl json) throws IOException {
		JsonMeta meta = jsonMetaManager.jsonMeta(json);
		DataMeta namedm = meta.namesMeta();
		DataMeta datadm = meta.datametaMeta();
		
		if(namedm == null || namedm.size == 0 || datadm == null || datadm.size == 0) 
			return parseJson(json);
		
		List<TemplateImpl> result = null; 
		
		try(StringResources r = StringResources.get()) {
			ByteBuffer buffer = r.buffer;
			StringBuilder sb = r.sb();
			
			smallfile.readText(namedm, buffer, r.chars, r.decoder, sb);
			if(sb.length() == 0)
				return new ArrayList<>();
			
			StringSplitIterator itr = new StringSplitIterator(sb, '\t');
			
			buffer.clear();
			buffer = smallfile.read(datadm, buffer);
			result = new ArrayList<>();
			
			int order = 0;
			
			while (itr.hasNext()) {
				String id = itr.next();
				String prefix = itr.next();
				result.add(json.template(order++, id, prefix, readMeta(buffer)));
			}
		}
		
		return result == null ? parseJson(json) : result;
	}

	private List<TemplateImpl> parseJson(JsonFileImpl json) throws IOException {
		Path p = json.source();
		if(!Files.isRegularFile(p))
			throw new FileNotFoundException("file not found: "+p);

		List<TemplateImpl> templates = new ArrayList<>();
		parseJson(p, StandardCharsets.UTF_8, (order, key, jsonObj) -> templates.add(json.template(order, key, jsonObj)));
		
		if(templates.isEmpty())
			return templates;
		
		batchWrite = true;
		
		try(StringResources r = StringResources.get()) {
			StringBuilder sb = r.sb();
			StringWriter2 sw = new StringWriter2(sb);
			
			for (TemplateImpl t : templates)
				json.writeCache(t, sw, r);	
			
			sb.setLength(0);
			for (TemplateImpl t : templates)
				sb.append(t.id()).append('\t').append(t.prefix()).append('\t');
			
			JsonMeta meta = jsonMetaManager.jsonMeta(json);
			r.buffer.clear();
			meta.namesMeta(smallfile.write(sb, r.encoder, r.buffer));
			
			r.buffer.clear();
			sb.setLength(0);
			
			int size = DataMeta.BYTES * templates.size();
			ByteBuffer buffer;
			if(r.buffer.capacity() < size) {
				buffer = ByteBuffer.allocate(size);
				logger.debug("bytbuffer created: {} -> {}", r.buffer.capacity(), buffer.capacity());
			} else {
				buffer = r.buffer;
			}
			
			buffer.clear();
			for (TemplateImpl t : templates)
				putMeta(t.getMeta(), buffer);
			
			buffer.flip();
			meta.datametaMeta(smallfile.write(buffer));
			buffer.clear();
			
			jsonMetaMod.set(json.id, false);
			logger.info("loaded json({}): {}", ANSI.yellow(templates.size()), json);
		} finally {
			batchWrite = false;
		}

		return templates;
	}


	public Path subpath(Path source) {
		return source.subpath(SNIPPET_DIR_COUNT, source.getNameCount());
	}
}
