/* Credits to the author of the code inspired on:
https://stackoverflow.com/questions/28201731/weka-java-code-kmeans-clustering
 */

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

import weka.clusterers.SimpleKMeans;
import weka.core.*;

public class TermsClusters {

    private TermsClusters() { }

    public static void main(String[] args) {
        String usage = "java es.udc.fic.ri.mri_indexer.TermsClusters"
                + " [-index INDEX_PATH] [-field FIELD] [-term TERM] [-top N] [-rep [bin,tf,tfxidf]] [-k CLUSTERS]\n\n";

        String indexPath = null;
        String fieldName = null;
        String termName = null;
        int top = -1;
        String rep = null;
        int k = -1;

        Directory dir = null;
        IndexReader indexReader = null;

        for(int i=0; i<args.length; i++) {
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
            } else if ("-k".equals(args[i])) {
                k = Integer.parseInt(args[++i]);
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
        } else if (k < 1) {
            System.err.println("k must be greather than 0: " + k);
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

            /* Create dataset for SimpleKMeans */
            ArrayList<Attribute> attrList = new ArrayList<Attribute>();
            for (int i=0; i<numDocs; i++) {
                attrList.add(new Attribute("doc" + i));
            }
            Instances dataset = new Instances("test", attrList, 0);

            /* Sort and print similarities and populate dataset */
            rankingTerms.sort(Comparator.comparingDouble(TermSimilarity::getSimilarity).reversed());

            System.out.printf("Top %d similar terms to %s%n%n", top, termName);
            System.out.printf("%-20s%-10s%n", "TERM", "SIMILARITY");

            int n = Math.min(top, rankingTerms.size());
            for (int i=0; i<n; i++) {
                TermSimilarity term = rankingTerms.get(i);
                System.out.printf("%-20s%-10f%n", term.getName(), term.getSimilarity());

                double[] val = vectors.get(term.getName()).toArray();
                Instance instance = new SparseInstance(1, val);
                dataset.add(instance);
            }
            System.out.println();

            /* Clustering */
            SimpleKMeans kmeans = new SimpleKMeans();
            try {
                kmeans.setPreserveInstancesOrder(true);
                kmeans.setNumClusters(k);
                kmeans.setSeed(2);
                kmeans.setDontReplaceMissingValues(true);
                kmeans.buildClusterer(dataset);
                kmeans.setMaxIterations(50);

                int[] assignments = kmeans.getAssignments();
                Map<Integer, List<String>> clusters = new HashMap<>();

                for (int i=0; i<n; i++) {
                    int assignment = assignments[i];
                    if (!clusters.containsKey(assignment)) {
                        clusters.put(assignment, new LinkedList<>());
                    }
                    clusters.get(assignment).add(rankingTerms.get(i).getName());
                }

                /* Print result */
                for (int i=0; i<k; i++) {
                    System.out.println("************* CLUSTER " + (i+1) + " *************");

                    if (clusters.containsKey(i)) {
                        for (String text : clusters.get(i)) {
                            System.out.println(text);
                        }
                    } else {
                        System.out.println("No terms clustered");
                    }

                }

            } catch (Exception e) {
                e.printStackTrace();
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
