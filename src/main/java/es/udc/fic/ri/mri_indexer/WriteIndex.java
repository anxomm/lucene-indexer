package es.udc.fic.ri.mri_indexer;

import org.apache.lucene.index.*;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Date;

public class WriteIndex {

    private WriteIndex() { }

    public static void main(String[] args) {
        String usage = "java java es.udc.fic.ri.mri_indexer.WriteIndex [-index INDEX_PATH] [-outputfile FILE]";

        String indexPath = null;
        String output = null;

        BufferedWriter writer = null;
        Directory dir = null;
        DirectoryReader indexReader = null;

        for (int i=0; i<args.length; i++) {
            if ("-index".equals(args[i])) {
                indexPath = args[++i];
            } else if ("-outputfile".equals(args[i])) {
                output = args[++i];
            }
        }

        if (indexPath == null || output == null) {
            System.err.println(usage);
            System.exit(-1);
        }

        Date start = new Date();
        try {
            writer = Files.newBufferedWriter(Paths.get(output));
            dir = FSDirectory.open(Paths.get(indexPath));
            indexReader = DirectoryReader.open(dir);

            FieldInfos fieldInfos = FieldInfos.getMergedFieldInfos(indexReader);
            for (final FieldInfo fieldInfo : fieldInfos) {
                writer.write(fieldInfo.name + "\n");

                final Terms terms = MultiTerms.getTerms(indexReader, fieldInfo.name);
                if (terms != null) {
                    final TermsEnum termsEnum = terms.iterator();
                    while (termsEnum.next() != null) {
                        BytesRef term = termsEnum.term();
                        writer.write(term.utf8ToString() + " ");
                    }
                    writer.write("\n\n");
                } else {
                    writer.write("No terms stored\n\n");
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                if (writer != null) {
                    writer.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
            try {
                if (indexReader != null) {
                    indexReader.close();
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
}
