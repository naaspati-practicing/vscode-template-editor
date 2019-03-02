package sam.pkg.jsonfile;

import sam.io.infile.DataMeta;

class JsonMeta {
	final long lastmodified;
	final DataMeta templateMeta;
	
	public JsonMeta(long lastmodified, DataMeta templateMeta) {
		this.lastmodified = lastmodified;
		this.templateMeta = templateMeta;
	}

	@Override
	public String toString() {
		return "JsonMeta [lastmodified=" + lastmodified + ", templateMeta=" + templateMeta + "]";
	}
}
