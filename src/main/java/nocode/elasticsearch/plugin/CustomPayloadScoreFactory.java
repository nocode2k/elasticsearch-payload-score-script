package nocode.elasticsearch.plugin;

import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.Term;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.script.DocReader;
import org.elasticsearch.script.DocValuesDocReader;
import org.elasticsearch.script.ScoreScript;
import org.elasticsearch.search.lookup.SearchLookup;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;

public final class CustomPayloadScoreFactory implements ScoreScript.LeafFactory {
    private final Map<String, Object> params;
    private final SearchLookup lookup;
    private final String field;
    private final String term;

    public CustomPayloadScoreFactory(Map<String, Object> params, SearchLookup lookup) {

        if (params.containsKey("field") == false) {
            throw new IllegalArgumentException("Missing parameter [field]");
        }
        if (params.containsKey("term") == false) {
            throw new IllegalArgumentException("Missing parameter [term]");
        }
        this.params = params;
        this.lookup = lookup;
        field = params.get("field").toString();
        term = params.get("term").toString();
    }

    @Override
    public boolean needs_score() {
        return false;
    }

    @Override
    public ScoreScript newInstance(DocReader docReader) throws IOException {

        DocValuesDocReader dvReader = (DocValuesDocReader) docReader;
        PostingsEnum postings = dvReader.getLeafReaderContext().reader().postings(new Term(field, term), PostingsEnum.PAYLOADS);

        if (postings == null) {
            return new ScoreScript(params, lookup, docReader) {
                @Override
                public double execute( ExplanationHolder explanation) {
                    return 0.0d;
                }
            };
        }

        return new ScoreScript(params, lookup, docReader) {
            int currentDocid = -1;

            @Override
            public void setDocument(int docid) {
                /*
                 * advance has undefined behavior calling with
                 * a docid <= its current docid
                 */
                if (postings.docID() < docid) {
                    try {
                        postings.advance(docid);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }
                currentDocid = docid;
            }

            @Override
            public double execute(ExplanationHolder explanation) {

                if (postings.docID() != currentDocid) {
                    /*
                     * advance moved past the current doc, so this doc
                     * has no occurrences of the term
                     */
                    return 0.0d;
                }
                try {
                    int freq = postings.freq();
                    float sumPayload = 0.0f;
                    for(int i = 0; i < freq; i ++) {
                        postings.nextPosition();
                        BytesRef payload = postings.getPayload();
                        if(payload != null) {
                            sumPayload += ByteBuffer.wrap(payload.bytes, payload.offset, payload.length)
                                    .order(ByteOrder.BIG_ENDIAN).getFloat();
                        }
                    }
                    return sumPayload;

                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        };
    }
}
