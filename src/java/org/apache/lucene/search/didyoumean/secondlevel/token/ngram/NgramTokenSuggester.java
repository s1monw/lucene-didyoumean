package org.apache.lucene.search.didyoumean.secondlevel.token.ngram;
/*
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */


import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.index.facade.IndexFacade;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.facade.IndexWriterFacade;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.*;
import org.apache.lucene.search.didyoumean.EditDistance;
import org.apache.lucene.search.didyoumean.Levenshtein;
import org.apache.lucene.search.didyoumean.Suggestion;
import org.apache.lucene.search.didyoumean.SuggestionPriorityQueue;
import org.apache.lucene.search.didyoumean.secondlevel.token.TokenSuggester;
import org.apache.lucene.search.didyoumean.secondlevel.token.TokenSuggestion;
import org.apache.lucene.util.Version;

import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/**
 * A single token word suggester based on n-grams.
 * <p/>
 * Uses Lucene for persistency and token n-gram matching.
 *
 * @author initially inspired by the David Spencer code.
 * @author Nicolas Maisonneuve
 * @author Karl Wettin <karl.wettin@gmail.com>
 *         Date: 2007-feb-03
 *         Time: 05:29:56
 */
public class NgramTokenSuggester implements TokenSuggester {

  private IndexFacade ngramIndex;
  private IndexReader ngramReader;
  private IndexSearcher ngramSearcher;

  public NgramTokenSuggester(IndexFacade ngramIndex) throws IOException {
    this.ngramIndex = ngramIndex;
    ngramReader = ngramIndex.indexReaderFactory();
    ngramSearcher = new IndexSearcher(ngramReader);
  }


  public IndexReader getNgramReader() {
    return ngramReader;
  }

  public IndexSearcher getNgramSearcher() {
    return ngramSearcher;
  }

  public IndexFacade getNgramIndex() {
    return ngramIndex;
  }

  private int defaultHitEnumerationsPerSuggestion = 10;

  /**
   * Field name for each word in the ngram index.
   */
  public static final String F_WORD = "word";

  /**
   * Boost value for start and end grams
   */
  private float bStart = 2.0f;
  private float bEnd = 1.0f;

  // minimum score for hits generated by the spell checker query
  private float minScore = 0.5f;

  /**
   * Sets the accuracy 0 &lt; minScore &lt; 1; default 0.5
   */
  public void setAccuracy(float min) {
    this.minScore = min;
  }

  private boolean defaultSuggestSelf = false;
  private boolean defaultSuggestOnlyMorePopularTokens = false;

  public SuggestionPriorityQueue suggest(String queryToken, int n) throws IOException {
    return suggest(queryToken, n, defaultSuggestSelf, null, null, false, getDefaultHitEnumerationsPerSuggestion());
  }

  public SuggestionPriorityQueue suggest(String queryToken, int n, boolean suggestSelf, IndexReader aprioriIndexReader, String aprioriIndexField, boolean selectMorePopularTokensOnly) throws IOException {
    return suggest(queryToken, n, suggestSelf, aprioriIndexReader, aprioriIndexField, selectMorePopularTokensOnly, getDefaultHitEnumerationsPerSuggestion());
  }

  /**
   * Suggest similar words (restricted or not to a field of a user index)
   *
   * @param queryToken                   the word you want a spell check done on
   * @param maxSuggestions               the number of suggest words
   * @param suggestSelf                  if true, a suggestion can be the queried token.
   * @param aprioriIndexReader
   * @param aprioriIndexField            the field of the user index: if field is not null, the suggested
   *                                     words are restricted to the words present in this field.
   * @param suggestOnyMorePopularTokens  if true, suggest only tokens that are more frequent than the query token
   *                                     (only if restricted mode = (aprioriIndex!=null and aprioriIndexField!=null)
   * @param hitEnumerationsPerSuggestion number of ngram document to measure edit distance on for each number of expected returned suggestions. @return suggestions the query token
   * @throws IOException if something went wrong in either aprioriIndex or ngramIndex.
   */
  public SuggestionPriorityQueue suggest(String queryToken, int maxSuggestions, boolean suggestSelf, IndexReader aprioriIndexReader,
                                         String aprioriIndexField, boolean suggestOnyMorePopularTokens, int hitEnumerationsPerSuggestion) throws IOException {

    SuggestionPriorityQueue<Suggestion> queue = new SuggestionPriorityQueue<Suggestion>(maxSuggestions);

    float minScore = this.minScore;
    final EditDistance editDistance = editDistanceFactory(queryToken);
    final int tokenLength = queryToken.length();

    final int goalFreq = (suggestOnyMorePopularTokens && aprioriIndexReader != null) ? aprioriIndexReader.docFreq(new Term(aprioriIndexField, queryToken)) : 0;
    // if the word exists in the real index and we don't care for word frequency, return the word itself
    if (!suggestOnyMorePopularTokens && goalFreq > 0) {
      queue.add((new Suggestion(queryToken)));
      return queue;
    }

    BooleanQuery query = new BooleanQuery();
    String[] grams;
    String key;

    for (int ng = getMin(tokenLength); ng <= getMax(tokenLength); ng++) {

      key = "gram" + ng; // form key

      grams = formGrams(queryToken, ng); // form word into ngrams (allow dups too)

      if (grams.length == 0) {
        continue; // hmm
      }

      if (bStart > 0) { // should we boost prefixes?
        add(query, "start" + ng, grams[0], bStart); // matches start of word

      }
      if (bEnd > 0) { // should we boost suffixes
        add(query, "end" + ng, grams[grams.length - 1], bEnd); // matches end of word

      }
      for (String gram : grams) {
        add(query, key, gram);
      }
    }

    // go thru more than 'maxr' matches in case the distance filter triggers
    TokenSuggestion suggestion = new TokenSuggestion();
    int stop = maxSuggestions * hitEnumerationsPerSuggestion;
    TopDocs hits = ngramSearcher.search(query, stop);

    for (int currentHit = 0; currentHit < hits.totalHits && currentHit < stop; currentHit++) {
      // get orig word
      Document doc = ngramReader.document(hits.scoreDocs[currentHit].doc);
      suggestion.setSuggested(doc.get(F_WORD));

      // don't suggest a word for itself, that would be silly
      if (!suggestSelf && suggestion.getSuggested().equals(queryToken)) {
        continue;
      }

      // edit distance/normalize with the minScore word length
      suggestion.setScore(1.0d - ((double) editDistance.getDistance(suggestion.getSuggested()) / Math
          .min(suggestion.getSuggested().length(), tokenLength)));
      if (suggestion.getScore() < minScore) {
        continue;
      }

      if (aprioriIndexReader != null) { // use the user index
        suggestion.setFrequency(aprioriIndexReader.docFreq(new Term(aprioriIndexField, suggestion.getSuggested()))); // freq in the index
        // don't suggest a word that is not present in the field
        if ((suggestOnyMorePopularTokens && goalFreq > suggestion.getFrequency()) || suggestion.getFrequency() < 1) {
          continue;
        }
      }
      queue.add(suggestion);


      suggestion = new TokenSuggestion();
    }

    return queue;
  }

  /**
   * Add a clause to a boolean query.
   */
  private void add(BooleanQuery q, String name, String value, float boost) {
    Query tq = new TermQuery(new Term(name, value));
    tq.setBoost(boost);
    q.add(new BooleanClause(tq, BooleanClause.Occur.SHOULD));
  }

  /**
   * Add a clause to a boolean query.
   */
  private void add(BooleanQuery q, String name, String value) {
    q.add(new BooleanClause(new TermQuery(new Term(name, value)), BooleanClause.Occur.SHOULD));
  }

  /**
   * Form all ngrams for a given word.
   *
   * @param text the word to parse
   * @param ng   the ngram length e.g. 3
   * @return an array of all ngrams in the word and note that duplicates are not removed
   */
  private String[] formGrams(String text, int ng) {
    int len = text.length();
    String[] res = new String[len - ng + 1];
    for (int i = 0; i < len - ng + 1; i++) {
      res[i] = text.substring(i, i + ng);
    }
    return res;
  }

  /**
   * Index a Dictionary
   *
   * @param tokens the dictionary to index
   * @throws IOException
   */
  public void indexDictionary(Iterator<String> tokens) throws IOException {
    indexDictionary(tokens, 3);
  }

  /**
   * Index a Dictionary
   *
   * @param tokens         the dictionary to index
   * @param minTokenLength minimum size of token to be suggestable. 2 if you want "on" to suggest "in".
   * @throws IOException
   */
  public void indexDictionary(Iterator<String> tokens, int minTokenLength) throws IOException {
    if (minTokenLength < 2) {
      minTokenLength = 2;
    }
    IndexWriterFacade writer = ngramIndex.indexWriterFactory(new StandardAnalyzer(Version.LUCENE_CURRENT, Collections.EMPTY_SET), false);
    //writer.setMergeFactor(300);
    //writer.setMaxBufferedDocs(150);

    Set<String> unflushedTokens = new HashSet<String>(1000);

    while (tokens.hasNext()) {
      String token = tokens.next();

      int len = token.length();
      if (len < minTokenLength) {
        continue; // too short we bail but "too long" is fine...
      }

      if (unflushedTokens.contains(token) || ngramReader.docFreq(new Term(F_WORD, token)) > 0) {
        continue;
      }

      // ok index the word
      Document doc = createDocument(token, getMin(len), getMax(len));
      writer.addDocument(doc);
      unflushedTokens.add(token);

    }

    writer.optimize();
    writer.close();

    IndexSearcher oldSearcher = ngramSearcher;
    IndexReader oldReader = ngramReader;

    ngramReader = ngramIndex.indexReaderFactory();
    ngramSearcher = new IndexSearcher(ngramReader);

    oldSearcher.close();
    oldReader.close();

  }

  private int getMin(int l) {
    if (l > 5) {
      return 3;
    }
    if (l == 5) {
      return 2;
    }
    return 1;
  }

  private int getMax(int l) {
    if (l > 5) {
      return 4;
    }
    if (l == 5) {
      return 3;
    }
    return 2;
  }

  private Document createDocument(String text, int ng1, int ng2) {
    Document doc = new Document();
    doc.add(new Field(F_WORD, text, Field.Store.YES, Field.Index.NOT_ANALYZED)); // orig term
    addGram(text, doc, ng1, ng2);
    return doc;
  }

  private void addGram(String text, Document doc, int ng1, int ng2) {
    int len = text.length();
    for (int ng = ng1; ng <= ng2; ng++) {
      String key = "gram" + ng;
      String end = null;
      for (int i = 0; i < len - ng + 1; i++) {
        String gram = text.substring(i, i + ng);
        doc.add(new Field(key, gram, Field.Store.YES, Field.Index.NOT_ANALYZED));
        if (i == 0) {
          doc.add(new Field("start" + ng, gram, Field.Store.YES, Field.Index.NOT_ANALYZED));
        }
        end = gram;
      }
      if (end != null) { // may not be present if len==ng1
        doc.add(new Field("end" + ng, end, Field.Store.YES, Field.Index.NOT_ANALYZED));
      }
    }
  }


  public boolean isDefaultSuggestSelf() {
    return defaultSuggestSelf;
  }

  public void setDefaultSuggestSelf(boolean defaultSuggestSelf) {
    this.defaultSuggestSelf = defaultSuggestSelf;
  }

  public boolean isDefaultSuggestOnlyMorePopularTokens() {
    return defaultSuggestOnlyMorePopularTokens;
  }

  public void setDefaultSuggestOnlyMorePopularTokens(boolean defaultSuggestOnlyMorePopularTokens) {
    this.defaultSuggestOnlyMorePopularTokens = defaultSuggestOnlyMorePopularTokens;
  }


  public int getDefaultHitEnumerationsPerSuggestion() {
    return defaultHitEnumerationsPerSuggestion;
  }

  public void setDefaultHitEnumerationsPerSuggestion(int defaultHitEnumerationsPerSuggestion) {
    this.defaultHitEnumerationsPerSuggestion = defaultHitEnumerationsPerSuggestion;
  }

  
  public EditDistance editDistanceFactory(String sa) {
    return new Levenshtein(sa);
  }
}
