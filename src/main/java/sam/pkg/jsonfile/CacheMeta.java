package sam.pkg.jsonfile;

import static sam.myutils.ThrowException.illegalArgumentException;

class CacheMeta  {
	public static final CacheMeta DEFAULT = new CacheMeta();
	
	private final int position, size;
	
	private CacheMeta() {
		this.position = -1;
		this.size = -1;
	} 

	public CacheMeta(int position, int size) {
		if(size < 0)
			illegalArgumentException("size ("+size+") < 0");
		
		if(position < 0)
			illegalArgumentException("position ("+position+") < 0");
		
		this.position = position;
		this.size = size;
	}
	public CacheMeta(long position, long size) {
		if(size < 0)
			illegalArgumentException("size ("+size+") < 0");
		
		if(position < 0 || position > Integer.MAX_VALUE )
			illegalArgumentException(String.format("out of bound [%d, %d) position=%d", 0, Integer.MAX_VALUE, position));
		
		this.position = (int) position;
		this.size = (int) size;
	}
	public int position() {
		return position;
	}
	public int size() {
		return size;
	}

	@Override
	public String toString() {
		return "Range [position=" + position + ", size=" + size + "]";
	}

	public static CacheMeta of(long position, long size) {
		if(position == -1 && size == -1)
			return DEFAULT;
		return new CacheMeta(position, size);
	}
}
