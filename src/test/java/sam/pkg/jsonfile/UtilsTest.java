package sam.pkg.jsonfile;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Random;

import org.junit.jupiter.api.Test;

import sam.io.IOUtils;
import sam.io.infile.DataMeta;

class UtilsTest {
	@Test
	void readTest() throws IOException {
		Random r = new Random();
		DataMeta[] expectedDms = new DataMeta[100];
		int[] expectedIds = new int[100];
		
		for (int i = 0; i < expectedDms.length; i++) {
			expectedDms[i] = new DataMeta(r.nextLong(), r.nextInt());
			expectedIds[i] = r.nextInt();
		}
		Path p = Files.createTempFile(null, null);
		ByteBuffer buffer = ByteBuffer.allocate(100);
		
		try(FileChannel fc = FileChannel.open(p, StandardOpenOption.WRITE)) {
			int loops =0;
			for (int i = 0; i < expectedIds.length; i++) {
				if(buffer.remaining() <  Utils.DATA_BYTES) {
					loops++;
					IOUtils.write(buffer, fc, true);
				}
				
				buffer.putInt(expectedIds[i])
				.putLong(expectedDms[i].position)
				.putInt(expectedDms[i].size);
			}
			
			System.out.println("loops: "+loops);
			IOUtils.write(buffer, fc, true);
		}
		
		System.out.println(Arrays.toString(expectedIds));
		System.out.println(Arrays.toString(expectedDms));
		
		DataMeta[] actualDms = new DataMeta[100];
		int[] actualIds = new int[100];
		System.out.println();
		
		try(FileChannel fc = FileChannel.open(p, StandardOpenOption.READ)) {
			int j[] = {0}; 
			Utils.readDataMetasByCount(fc, 0, 100, buffer, (i, m) -> {
				int n = j[0]++;
				
				actualDms[n] = m;
				actualIds[n] = i;
				
				System.out.printf("%14s: %s\n", i, m);
			});	
		}
		
		assertArrayEquals(expectedIds, actualIds);
		
		for (int i = 0; i < expectedDms.length; i++) {
			DataMeta ed = expectedDms[i];
			DataMeta ad = actualDms[i];
			
			assertEquals(ed.position, ad.position);
			assertEquals(ed.size, ad.size);
		}
	}
}
