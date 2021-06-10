package es.udc.fic.ri.mri_indexer;

import java.io.*;

import java.nio.file.Paths;
import java.util.Date;

import org.apache.lucene.index.CorruptIndexException;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.FieldInfos;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.search.CollectionStatistics;
import org.apache.lucene.search.IndexSearcher;

public class StatsField {
	public static void main(String[] args) throws IOException {
		String usage = "java org.apache.lucene.demo.IndexFiles"
	            + " [-index INDEX_PATH] [-field FIELD_NAME]";
		 
		String indexPath = null;
		String field = null;

		Directory dir = null;
		DirectoryReader reader = null;
		IndexSearcher searcher = null;

		for (int i=0; i<args.length; i++) {
			 if ("-index".equals(args[i])) {
	             indexPath = args[++i];
	         } else if ("-field".equals(args[i])){
	        	 field = args[++i];
	         }
		}

		if (indexPath == null) {
			System.err.println(usage);
			System.exit(-1);
		}

		Date start = new Date();
		try {
			dir = FSDirectory.open(Paths.get(indexPath));
			reader = DirectoryReader.open(FSDirectory.open(Paths.get(indexPath)));
			searcher = new IndexSearcher(reader);

			if (field == null) { // returns stats of all index terms
				FieldInfos fieldInfos = FieldInfos.getMergedFieldInfos(reader);
				for (final FieldInfo fieldInfo : fieldInfos) {
					CollectionStatistics fieldStats = searcher.collectionStatistics(fieldInfo.name);
					printStatistics(fieldInfo.name, fieldStats);
				}
			} else {
				CollectionStatistics fieldStats = searcher.collectionStatistics(field);
				printStatistics(field, fieldStats);
			}
		} catch (CorruptIndexException e1) {
			System.out.println("Graceful message: exception " + e1);
			e1.printStackTrace();
		} catch (IOException e1) {
			System.out.println("Graceful message: exception " + e1);
			e1.printStackTrace();
		} finally {
			try {
				if (reader != null) {
					reader.close();
				}
			} catch (IOException e) {
				e.printStackTrace();
			}
			try {
				if (dir != null) {
					dir.close();
				}
			} catch (IOException e) {
				e.printStackTrace();
			}
		}

		Date end = new Date();
		System.out.println(end.getTime() - start.getTime() + " total milliseconds");
	}

	private static void printStatistics(String field, CollectionStatistics statistics) {
		System.out.printf("%-20s%-20s%n", "FIELD", field);
		System.out.printf("%-20s%-20d%n", "MAX_DOC", statistics == null ? -1 : statistics.maxDoc());
		System.out.printf("%-20s%-20d%n", "SUM_DOC_FREQ", statistics == null ? -1 : statistics.sumDocFreq());
		System.out.printf("%-20s%-20d%n", "SUM_TOTAL_TERM_FREQ", statistics == null ? -1 : statistics.sumTotalTermFreq());
		System.out.printf("%-20s%-20d%n%n", "DOC_COUNT", statistics == null ? -1 : statistics.docCount());
	}

}