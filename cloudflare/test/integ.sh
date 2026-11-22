#!/bin/bash
H=localhost:8787; T="Authorization: Bearer testtoken"
up(){ curl -s -X POST -H "$T" --data-binary @"$1" "http://$H/v1/up?name=$(basename $1)&cat=$2$3"; echo; }
echo "--- no token"; curl -s -X POST --data-binary @/tmp/g1.jar "http://$H/v1/up" ; echo
echo "--- g1"; up /tmp/g1.jar Hanh%20dong | python3 -c "import sys,json; j=json.load(sys.stdin); j['post']['html']=j['post']['html'][:300]; print(json.dumps(j,ensure_ascii=False,indent=1))"
echo "--- g1 again (dup)"; up /tmp/g1.jar Hanh%20dong | python3 -c "import sys,json; j=json.load(sys.stdin); print(j['duplicate'], j['item'], j['store'])"
echo "--- g2"; up /tmp/g2.jar "" | head -c 400; echo
echo "--- g3 similar"; up /tmp/g3.jar "" | python3 -c "import sys,json; j=json.load(sys.stdin); print(j.get('similar'), j['fname'])"
echo "--- notjar"; up /tmp/notjar.jar ""
echo "--- big (r2 limit 100000)"; up /tmp/big.jar "" | python3 -c "import sys,json; j=json.load(sys.stdin); print(j['store'], j.get('fallback'))"
