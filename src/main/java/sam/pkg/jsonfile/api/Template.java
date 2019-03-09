package sam.pkg.jsonfile.api;

import java.io.IOException;

public interface Template {
	static final String PREFIX = "prefix";
	static final String BODY = "body";
	static final String DESCRIPTION = "description";

	String id();
	String prefix();
	String description();
	String body();
	
	void prefix(String s);
	void description(String s);
	void body(String s);
	void save() throws IOException;
	JsonFile parent();
}
