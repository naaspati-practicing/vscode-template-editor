package sam.pkg;

import org.json.JSONObject;

public class Key {
	public final JsonFile jsonFile;
	public final String key,key_lower, entryId;
	
	public Key(JsonFile jsonFile, String key, String entryId) {
		this.jsonFile = jsonFile;
		this.key = key;
		key_lower = key.toLowerCase();
		this.entryId = entryId;
	}
	
	@Override
	public String toString() {
		return key;
	}

	public JSONObject object() {
		return jsonFile.jsonObject(this);
	}
	public void remove() {
		jsonFile.remove(this);
	}

	public void setSaved() {
		jsonFile.setSaved(this);
	}
}
