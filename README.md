# Lucene indexer utilities

## Introduction

This project provides with some useful classes to index documents and
also to analyse the data of a Lucene index.

Tests have been carried out with Java 8, Lucene 8.1.1 and the
[NPL collection](http://ir.dcs.gla.ac.uk/resources/test_collections/npl/).

## Setup

In order to make the setup process easier, a _pom.xml_ was added, so importing
it as a Maven project should be enough.

## IndexFiles

Indexes the documents into a Lucene index. The options available are:

- -index \<path\>: path where the index will be created
- -openmode \<mode\>: the mode can be
  - create: create a whole new index
  - append: add documents to an existing index
  - create_or_append: create a new index if it does not exist, otherwise is the
  same as _append_
- -update: updates the documents already in the index (with the same path)
- -numThreads \<n\>: number of threads used during the process
- -onlyFiles: index only the documents specified in _config.properties_
- -partialIndexes: create a partial index per top level folder or document
specified in _config.properties_. After finishing, they are merged into one
unique index

As you can observe, some arguments depends on some variables specified in a
_config.properties_ file, located at _src/main/resources/_. The variables are:

- docs: top level folders or files to be indexed
- partialIndexes: paths where partial indexes
will be created (if -partialIndexes specified). Notice that it must contain as
many paths as the _docs_ variable
- onlyFiles: index only documents with the given
format
- onlyTopLines: index only the first _n_ lines of the documents
- onlyBottomLines: index only the last _m_ lines of the documents

If onlyTopLines and onlyBottomLines are specified at the same time then the first
_n_ and last _m_ lines will be indexed.

## StatsField

Shows basic statistics about the collection.

- -index \<path>\: path of the index
- -field \<field_name\>: field whose statistics will be shown. If not specified,
statistics about all the fields will be shown.

## WriteIndex

Writes the content of the collection into a plane text.

- -index \<path>\: path of the index
- -outputfile \<path\>: path of the output file

## BestTerms

Shows the top terms of a specific field and document ordered by a specific measure.

- -index \<path>\: path of the index
- -docID \<id\>: Lucene ID of the document
- -field \<field_name\>: field to analyse
- -top \<n\>: length of the ranking
- -order \<measure\>: measure to apply, it can be _tf_, _df_ or _tfxidf_
- -outputfile \<path\>: if given, the ranking is written into the file specified

## SimilarTerms

Gets the top terms similar (cosine similarity) to the one given.

- -index \<path>\: path of the index
- -field \<field_name>: field to analyse
- -term \<word\>: the reference to get the top similar terms of
- -top \<n\>: length of the ranking
- -rep \<mode\>: representation of each term vector, it can be
  - bin: takes the value 1 if it is present
  - tf: takes the total frecuency of the word in the field
  - tfxidf: tf * log2(N/df), where N is the number of different words in the
  field and df the number of documents where it is present

## TermsClusters

Does the sames as SimilarTerms, but also creates clusters to organise the
ranking in different groups.

- -index \<path>\: path of the index
- -field \<field_name>: field to analyse
- -term \<word\>: the reference to get the top similar terms of
- -top \<n\>: length of the ranking
- -rep \<mode\>: representation of each term vector, it can be
  - bin: takes the value 1 if it is present
  - tf: takes the total frecuency of the word in the field
  - tfxidf: tf * log2(N/df), where N is the number of different words in the
  field and df the number of documents where it is present
- -k \<k_clusters>: number of clusters to create

---

## Execution

Each class can be executed like an ordinary .java, but can be executed with
Maven as well. To do so, run:

```
mvn package
mvn exec:java -Dexec.mainClass="<class_name>" -Dexec.args="<args>"
```

Notice that the first command will create _.jar_ files, so the files could also
be executed like

```
java -jar target/<class_name>-0.0.1-SNAPSHOT-jar-with-dependencies.jar <args>
```
