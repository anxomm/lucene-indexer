package es.udc.fic.ri.mri_indexer;

import org.apache.lucene.index.*;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;

import java.io.*;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;

class TermStats {
    private String name;
    private long tf;
    private int df;
    private double tfxidf;

    public TermStats(String name, long tf, int df, int numDocs) {
        this.name = name;
        this.tf = tf;
        this.df = df;
        tfxidf = tf * Math.log10((double) numDocs / df);
    }

    public String getName() { return name; }
    public long getTf() { return tf; }
    public int getDf() { return df; }
    public double getTfxIdf() { return tfxidf; }
}

public class BestTerms {

    private BestTerms() { }

    public static void main(String[] args) {
        String usage = "java java es.udc.fic.ri.mri_indexer.BestTerms" +
        "[-index INDEX_PATH] [-docID ID] [-field NAME] [-top N] [-order [tf,df,tfxidf]] [-outputfile FILE]";

        String indexPath = null;
        int docId = -1;
        String fieldName = null;
        int top = -1;
        String order = null;
        String output = null;

        Directory dir = null;
        DirectoryReader indexReader = null;
        PrintStream writer = null;

        for(int i=0; i<args.length; i++) {
            if ("-index".equals(args[i])) {
                indexPath = args[++i];
            } else if ("-docID".equals(args[i])) {
                docId = Integer.parseInt(args[++i]);
            } else if ("-field".equals(args[i])) {
                fieldName = args[++i];
            } else if ("-top".equals(args[i])) {
                top = Integer.parseInt(args[++i]);
            } else if ("-order".equals(args[i])) {
                order = args[++i];
            } else if ("-outputfile".equals(args[i])) {
                output = args[++i];
            }
        }

        if (indexPath == null || fieldName == null || order == null) {
            System.err.println(usage);
            System.exit(-1);
        } else if (docId < 0) {
            System.err.println("docId must be greater than 0: " + docId);
            System.exit(-1);
        } else if (top < 1) {
            System.err.println("top must be greater than 0: " + docId);
            System.exit(-1);
        } else if (!(order.equals("tf") || order.equals("df") || order.equals("tfxidf"))) {
            System.err.println("Order must be 'tf', 'df' or 'tfxidf': " + order);
            System.exit(-1);
        }

        Date start = new Date();
        label : try {
            dir = FSDirectory.open(Paths.get(indexPath));
            indexReader = DirectoryReader.open(dir);
            int numDocs = indexReader.numDocs();

            /* Get the terms of a specific field */
            List<TermStats> termStats = new LinkedList<>();
            /*
            // If we had term vectors we could use this code
            final Terms terms = indexReader.getTermVector(docId, fieldName);
            if (terms == null) {
                System.err.println("No terms for field '" + fieldName + "' in document " + docId);
                break label;
            }

            List<TermStats> termStats = new LinkedList<>();
            final TermsEnum termsEnum = terms.iterator();
            while (termsEnum.next() != null) {
                BytesRef term = termsEnum.term();
                termStats.add(new TermStats(term.utf8ToString(), termsEnum.totalTermFreq(),
                        indexReader.docFreq(new Term(fieldName, term)), numDocs));
            }
            */

            final Terms terms = MultiTerms.getTerms(indexReader, fieldName);
            if (terms != null) {
                final TermsEnum termsEnum = terms.iterator();

                while (termsEnum.next() != null) {
                    BytesRef term = termsEnum.term();
                    PostingsEnum posting = MultiTerms.getTermPostingsEnum(indexReader, fieldName, term);

                    if (posting != null) {
                        int id;
                        while ((id = posting.nextDoc()) != PostingsEnum.NO_MORE_DOCS) {
                            if (id == docId) {
                                termStats.add(new TermStats(termsEnum.term().utf8ToString(),
                                        posting.freq(), termsEnum.docFreq(), numDocs));
                            }
                        }
                    }
                }
            }

            if (order.equals("tf")) {
                termStats.sort(Comparator.comparingDouble(TermStats::getTf).reversed());
            } else if (order.equals("df")) {
                termStats.sort(Comparator.comparingDouble(TermStats::getDf).reversed());
            } else {
                termStats.sort(Comparator.comparingDouble(TermStats::getTfxIdf).reversed());
            }

            /* Redirect output to a file */
            PrintStream stdout = System.out;
            if (output != null) {
                File file = new File(output);
                if (!file.exists()) {
                    file.createNewFile();
                }
                writer = new PrintStream(file);
                System.setOut(writer);
            }

            System.out.printf("%-20s%-10s%-10s%-10s%n", "TERM", "TF", "DF", "TFxIDF");
            int n = Math.min(top, termStats.size());
            for (int i = 0; i < n; i++) {
                TermStats term = termStats.get(i);
                System.out.printf("%-20s%-10d%-10d%-10f%n", term.getName(), term.getTf(), term.getDf(), term.getTfxIdf());
            }

            /* Reset output */
            if (output != null) {
                System.setOut(stdout);
            }

        } catch (IOException e) {
            e.printStackTrace();
        } finally {
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
            if (writer != null) {
                writer.close();
            }
        }

        Date end = new Date();
        System.out.println("\n" + (end.getTime() - start.getTime()) + " total milliseconds");
    }
}
