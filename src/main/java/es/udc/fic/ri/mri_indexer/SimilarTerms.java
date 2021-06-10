package es.udc.fic.ri.mri_indexer;

import org.apache.commons.math3.linear.ArrayRealVector;
import org.apache.commons.math3.linear.RealVector;
import org.apache.lucene.index.*;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.*;

class TermSimilarity {
    private String name;
    private Double similarity;

    public TermSimilarity(String name, Double similarity) {
        this.name = name;
        this.similarity = similarity;
    }

    public String getName() { return name; }
    public Double getSimilarity() { return similarity; }
}

public class SimilarTerms {

    private SimilarTerms() { }

    public static void main(String[] args) {
        String usage = "java es.udc.fic.ri.mri_indexer.SimilarTerms"
                + " [-index INDEX_PATH] [-field FIELD] [-term TERM] [-top N] [-rep [bin,tf,tfxidf]]\n\n";

        String indexPath = null;
        String fieldName = null;
        String termName = null;
        int top = -1;
        String rep = null;

        Directory dir = null;
        IndexReader indexReader = null;

        for (int i=0; i<args.length; i++) {
            if ("-index".equals(args[i])) {
                indexPath = args[++i];
            } else if ("-field".equals(args[i])) {
                fieldName = args[++i];
            } else if ("-term".equals(args[i])) {
                termName = args[++i];
            } else if ("-top".equals(args[i])) {
                top = Integer.parseInt(args[++i]);
            } else if ("-rep".equals(args[i])) {
                rep = args[++i];
            }
        }

        if (indexPath == null || fieldName == null || termName == null || rep == null) {
            System.err.println(usage);
            System.exit(-1);
        } else if (!(rep.equals("bin") || rep.equals("tf") || rep.equals("tfxidf"))) {
            System.err.println("Unknown rep: " + rep);
            System.exit(-1);
        } else if (top < 0) {
            System.err.println("top must be positive: " + top);
            System.exit(-1);
        }

        Date start = new Date();
        label : try {
            dir = FSDirectory.open(Paths.get(indexPath));
            indexReader = DirectoryReader.open(dir);
            int numDocs = indexReader.numDocs();

            Map<String, RealVector> vectors = new LinkedHashMap<>();


            /* Get the vector of each term */
            final Terms terms = MultiTerms.getTerms(indexReader, fieldName);
            if (terms != null) {
                final TermsEnum termsEnum = terms.iterator();

                while (termsEnum.next() != null) {
                    BytesRef term = termsEnum.term();
                    String text = term.utf8ToString();

                    PostingsEnum posting = MultiTerms.getTermPostingsEnum(indexReader, fieldName, term);
                    int id;
                    while ((id = posting.nextDoc()) != PostingsEnum.NO_MORE_DOCS) {
                        if (vectors.containsKey(text)) {
                            vectors.get(text).setEntry(id, rep.equals("bin") ? 1 :
                                    rep.equals("tf") ? posting.freq() : posting.freq()*Math.log10((double) numDocs / termsEnum.docFreq()));
                        } else {
                            RealVector v = new ArrayRealVector(numDocs);
                            v.setEntry(id, rep.equals("bin") ? 1 : rep.equals("tf") ? posting.freq() :
                                    posting.freq() * Math.log10((double) numDocs / termsEnum.docFreq()));
                            vectors.put(text, v);
                        }
                    }
                }
            }

            if (!vectors.containsKey(termName)) {
                System.err.println("Term not found in the collection: " + termName);
                break label;
            }

            /* Calculate similarities against our term */
            RealVector termVector = vectors.get(termName);
            vectors.remove(termName);

            List<TermSimilarity> rankingTerms = new LinkedList<>();
            for (Map.Entry<String, RealVector> entry : vectors.entrySet()) {
                String text = entry.getKey();
                rankingTerms.add(new TermSimilarity(text, getCosineSimilarity(termVector, entry.getValue())));
            }

            /* Sort and print result */
            rankingTerms.sort(Comparator.comparingDouble(TermSimilarity::getSimilarity).reversed());

            System.out.printf("Top %d similar terms to %s%n%n", top, termName);
            System.out.printf("%-20s%-10s%n", "TERM", "SIMILARITY");

            int n = Math.min(top, rankingTerms.size());
            for (int i=0; i<n; i++) {
                TermSimilarity term = rankingTerms.get(i);
                System.out.printf("%-20s%-10f%n", term.getName(), term.getSimilarity());
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
        }

        Date end = new Date();
        System.out.println("\n" + (end.getTime() - start.getTime()) + " total milliseconds");
    }

    private static double getCosineSimilarity(RealVector v1, RealVector v2) {
        double out = (v1.dotProduct(v2)) / (v1.getNorm() * v2.getNorm());
        return Double.isNaN(out) ? 0.0 : out;
    }

}