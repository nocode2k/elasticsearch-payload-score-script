### elasticsearch : payload-score-script Plugin



T-Shirt  상품 중에서 선택 옵션의 판매수량이 많거나 재고 수량이 많은 (혹은 적은) 상품을 검색 순위에 포함하려면 어떻게 해야할까요?

(* payload-score-query Plugin에서는 검색결과를 결정하는 플러그인이었다면 payload-score-script Plugin은 검색 순위만 결정합니다.)

Lucene이 제공하는 PayloadScoreQuery를 사용하면 Term의 차이를  구분할 수 있습니다. Lucene에서는 실제적으로 우리가 저장한 Payload 데이터를 "|"와 같은 문자 뒤의 숫자를 구분하여 tf에 곱한 다음에 가중치 계산을 하고 있습니다.

안타깝게도 Elasticsearch에서는 Delimited payload token filter는 제공하고 있지만 PayloadScoreQuery와 같이 가중치를 계산하고 있지는 않습니다.

Elasticsearch 공식 문서: https://www.elastic.co/guide/en/elasticsearch/reference/8.5/analysis-delimited-payload-tokenfilter.html#analysis-delimited-payload-tokenfilter


T-Shirt  상품의 검색 순위를 높여야 하는 요구사항을 만족하기 위해서 Elasticsearch에서 Plugin개발을 통해서 PayloadScoreQuery 기능을 적용하는 방법을 살펴보도록 하겠습니다. 


## 환경

- open jdk 17
- gradle 7.4.1
- elasticsearch 8.5.3



## Analyzer 추가：

payload_delimiter라는 이름으로 analyzer를 설정한 paylaod_score_query 예제 index를 생성：

```json
PUT paylaod_score_query
{
  "mappings": {
    "properties": {
      "color": {
        "type": "text",
        "term_vector": "with_positions_payloads",
        "analyzer": "payload_delimiter"
      }
    }
  },
  "settings": {
    "analysis": {
      "analyzer": {
        "payload_delimiter": {
          "tokenizer": "whitespace",
          "filter": [ "delimited_payload" ]
        }
      }
    }
  }
}
```



paylaod_score_query 예제 index에 3개의 테스트 문서를 색인합니다.

```json
POST paylaod_score_query/_doc/1
{
  "name" : "T-shirt S",
  "color" : "blue|1 green|2 yellow|3"
}

POST paylaod_score_query/_doc/2
{
  "name" : "T-shirt M",
  "color" : "blue|1 green|2 red|3"
}

POST paylaod_score_query/_doc/3
{
  "name" : "T-shirt XL",
  "color" : "blue|1 yellow|2"
}

POST paylaod_score_query/_doc/4
{
  "name" : "T-shirt XL",
  "color" : "blue|1 yellow|10"
}

```



문서들의 토큰이 base64-encoded된 payload인지 확인합니다. 

GET paylaod_score_query/_termvectors/1?fields=color

```json
{
  "_index" : "paylaod_score_query",
  "_type" : "_doc",
  "_id" : "1",
  "_version" : 2,
  "found" : true,
  "took" : 26,
  "term_vectors" : {
    "color" : {
      "field_statistics" : {
        "sum_doc_freq" : 11,
        "doc_count" : 4,
        "sum_ttf" : 11
      },
      "terms" : {
        "blue" : {
          "term_freq" : 1,
          "tokens" : [
            {
              "position" : 0,
              "payload" : "P4AAAA=="
            }
          ]
        },
        "green" : {
          "term_freq" : 1,
          "tokens" : [
            {
              "position" : 1,
              "payload" : "QAAAAA=="
            }
          ]
        },
        "yellow" : {
          "term_freq" : 1,
          "tokens" : [
            {
              "position" : 2,
              "payload" : "QEAAAA=="
            }
          ]
        }
      }
    }
  }
}
```



## Plugin을 사용하지 않은 function_score Query 결과 확인：

payload_delimiter가 적용된 color 필드를 포함하여 function_score query를 실행합니다.

```json
GET /paylaod_score_query/_search
{
  "explain": false,
  "query": {
    "function_score": {
      "query": {
        "match": {
          "name": "t-shirt"
        }
      },
      "functions": [
        {
          "filter": { "match": { "color": "yellow" } },
          "random_score": {},
          "weight": 10
        }
      ]
    }
  }
}
```



아래의 실행 결과를 보면 Elasticsearch에서 payload score query를 지원하지 않기 때문에 color필드의 yellow|3 값을 가진 문서 _id 1의 가중치(score)가 yellow|10값을 가진 문서 _id 4보다 높은 것을 확인할 수 있습니다.

```json
{
  "took": 0,
  "timed_out": false,
  "_shards": {
    "total": 1,
    "successful": 1,
    "skipped": 0,
    "failed": 0
  },
  "hits": {
    "total": {
      "value": 4,
      "relation": "eq"
    },
    "max_score": 21.072102,
    "hits": [
      {
        "_index": "paylaod_score_query",
        "_id": "1",
        "_score": 21.072102,
        "_source": {
          "name": "T-shirt S",
          "color": "blue|1 green|2 yellow|3"
        }
      },
      {
        "_index": "paylaod_score_query",
        "_id": "3",
        "_score": 21.072102,
        "_source": {
          "name": "T-shirt XL",
          "color": "blue|1 yellow|2"
        }
      },
      {
        "_index": "paylaod_score_query",
        "_id": "4",
        "_score": 21.072102,
        "_source": {
          "name": "T-shirt XL",
          "color": "blue|1 yellow|10"
        }
      },
      {
        "_index": "paylaod_score_query",
        "_id": "2",
        "_score": 0.21072102,
        "_source": {
          "name": "T-shirt M",
          "color": "blue|1 green|2 red|3"
        }
      }
    ]
  }
}

```



------



**지금부터 payload데이터를 검색결과 가중치에 포함할 수 있도록 구현한 Elasticsearch Plugin의 Class와 주요 Method를 설명한 다음에 Plugin 설치 후 그 결과를 확인하겠습니다.**

## 참고) Advanced scripts using script engines

https://www.elastic.co/guide/en/elasticsearch/reference/8.5/modules-scripting-engine.html


## CustomPayloadScoreScriptPlugin

다음과 같이 CustomPayloadScoreScriptPlugin 클래스에 ScriptEngine을 상속받은 구현체를 추가합니다.

```java
private static class NoCodeScriptEngine implements ScriptEngine {
    private final String _SOURCE_VALUE = "payload_script";
    private final String _LANG_VALUE = "nocode";

    @Override
    public String getType() {
        return _LANG_VALUE;
    }

    @Override
    public <T> T compile(String scriptName, String scriptSource, ScriptContext<T> context, Map<String, String> params) {
        ... 생략 ...
    }
```



## CustomPayloadScoreFactory

### ScoreScript 메소드의 구현 ：

```js
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
```


## Build source code



```
$ gradle clean build
```



## Install plugin



```
$ cd $ES_HOME
$ ./bin/elasticsearch-plugin install file:///$PROJECT/build/distributions/payload-score-script-0.1.zip
```



## RUN Elasticsearch

```
$ cd $ES_HOME
$ ./bin/elasticsearch
```



## Sample API 실행

customize한 plugin의 payload_score api를 사용하여 function_score query를 실행합니다.

```json
GET /paylaod_score_query/_search
{
  "explain": false,
  "query": {
    "function_score": {
      "query": {
        "match": {
          "name": "t-shirt"
        }
      },
      "functions": [
        {
          "script_score": {
            "script": {
              "source": "payload_script",
              "lang" : "nocode",
              "params": {
                "field": "color",
                "term": "yellow"
              }
            }
          }
        }
      ]
    }
  }
}
```



아래의 API 응답결과를 확인해보면 일반적인 function_score Query를 실행한 결과와 다르게 yellow|10 이 포함된 문서 _id 4의 가중치(score)가 적용된 것을 확인할 수 있습니다.

```json
{
  "took": 2,
  "timed_out": false,
  "_shards": {
    "total": 1,
    "successful": 1,
    "skipped": 0,
    "failed": 0
  },
  "hits": {
    "total": {
      "value": 4,
      "relation": "eq"
    },
    "max_score": 2.1072102,
    "hits": [
      {
        "_index": "paylaod_score_query",
        "_id": "4",
        "_score": 2.1072102,
        "_source": {
          "name": "T-shirt XL",
          "color": "blue|1 yellow|10"
        }
      },
      {
        "_index": "paylaod_score_query",
        "_id": "1",
        "_score": 0.63216305,
        "_source": {
          "name": "T-shirt S",
          "color": "blue|1 green|2 yellow|3"
        }
      },
      {
        "_index": "paylaod_score_query",
        "_id": "3",
        "_score": 0.42144203,
        "_source": {
          "name": "T-shirt XL",
          "color": "blue|1 yellow|2"
        }
      },
      {
        "_index": "paylaod_score_query",
        "_id": "2",
        "_score": 0,
        "_source": {
          "name": "T-shirt M",
          "color": "blue|1 green|2 red|3"
        }
      }
    ]
  }
}

```




