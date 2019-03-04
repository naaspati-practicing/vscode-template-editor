package sam.pkg.jsonfile.infile;

import java.io.IOException;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Path;
import java.util.List;

import sam.io.infile.DataMeta;
import sam.nopkg.StringResources;
import sam.pkg.jsonfile.infile.JsonFileImpl.TemplateImpl;

interface JsonLoader {
	Path subpath(Path p);
	List<TemplateImpl> loadTemplates(JsonFileImpl jsonFile) throws IOException;
	StringBuilder loadText(TemplateImpl template, StringResources r) throws IOException;
	DataMeta meta(TemplateImpl t);
	void transfer(List<DataMeta> metas, WritableByteChannel channel) throws IOException;
	void write(TemplateImpl t, CharSequence sb, StringResources r) throws IOException;
}
