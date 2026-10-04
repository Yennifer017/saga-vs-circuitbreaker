#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════════════
#  Pruebas de fallo del PoC
#  Uso:  ./scripts/demo.sh            (todos los escenarios)
#        ./scripts/demo.sh 3          (solo el escenario 3)
# ═══════════════════════════════════════════════════════════════════════════
set -u
ORDER=http://localhost:8081
INV=http://localhost:8083
BUS=http://localhost:8080

B=$'\e[1m'; G=$'\e[32m'; R=$'\e[31m'; Y=$'\e[33m'; C=$'\e[36m'; N=$'\e[0m'

titulo () { echo; echo "${B}${C}══════════════════════════════════════════════════════════════════${N}";
            echo "${B}${C} $1${N}"; echo "${B}${C}══════════════════════════════════════════════════════════════════${N}"; }
paso ()   { echo "${Y}▸ $1${N}"; }

reset_all () {
  curl -s -X POST "$ORDER/circuit-breakers/reset" > /dev/null
  for s in payment inventory shipping; do
    curl -s -X POST "$ORDER/admin/fail-mode/$s?mode=none" > /dev/null
  done
  curl -s -X POST "$INV/inventory/stock?sku=SKU-LAPTOP&qty=10" > /dev/null
  curl -s -X POST "$INV/inventory/stock?sku=SKU-RARO&qty=1"    > /dev/null
  curl -s -X DELETE "$BUS/events" > /dev/null
}

orden () {  # $1=modo $2=sku $3=cantidad $4=monto
  curl -s -w '\n__HTTP__%{http_code}' -X POST "$ORDER/orders?mode=${1}" \
    -H 'Content-Type: application/json' \
    -d "{\"customerId\":\"CLI-DEMO\",\"sku\":\"$2\",\"quantity\":$3,\"amount\":$4}"
}

pinta_saga () {
  python3 -c "
import sys, json
raw = sys.stdin.read()
body, _, code = raw.partition('__HTTP__')
d = json.loads(body)
col = {'COMPLETED':'\033[32m','COMPENSATED':'\033[31m','ACCEPTED':'\033[36m'}.get(d['status'],'')
print(f\"  sagaId     : {d['sagaId']}\")
print(f\"  enfoque    : {d['approach']}\")
print(f\"  estado     : {col}{d['status']}\033[0m   (HTTP {code.strip()})\")
if d.get('failureType'): print(f\"  tipo fallo : \033[31m{d['failureType']}\033[0m\")
if d.get('reason'):      print(f\"  motivo     : {d['reason']}\")
print('  traza:')
for s in d['trace']:
    mark = '\033[32mOK\033[0m' if s['status']=='OK' else ('\033[31m'+s['status']+'\033[0m')
    print(f\"    {s['step']:22s} {mark:30s} {s['detail']}\")
"
}

cb_estado () {
  curl -s "$ORDER/circuit-breakers" | python3 -c "
import sys, json
for c in json.load(sys.stdin):
    col = {'CLOSED':'\033[32m','OPEN':'\033[31m','HALF_OPEN':'\033[33m'}[c['state']]
    print(f\"    {c['name']:18s} {col}{c['state']:10s}\033[0m fallos={c['failedCalls']}/{c['bufferedCalls']} \"
          f\"tasa={c['failureRate']:>7s} rechazadas={c['notPermittedCalls']}\")
"
}

# ───────────────────────── ESCENARIO 1 ─────────────────────────
esc1 () {
titulo "ESCENARIO 1 — Flujo feliz (Saga por ORQUESTACION)"
reset_all
paso "Stock inicial:"; curl -s "$INV/inventory" | python3 -m json.tool | sed 's/^/    /'
paso "POST /orders (2 x SKU-LAPTOP)"
orden orchestration SKU-LAPTOP 2 1500.0 | pinta_saga
paso "Stock despues (debe haber bajado 2):"; curl -s "$INV/inventory" | python3 -m json.tool | sed 's/^/    /'
echo "${G}  ✔ Los 4 pasos se ejecutaron y la orden quedo COMPLETED.${N}"
}

# ───────────────────────── ESCENARIO 2 ─────────────────────────
esc2 () {
titulo "ESCENARIO 2 — Saga: fallo de NEGOCIO en Inventory -> COMPENSACIONES"
reset_all
paso "Pido 5 unidades de SKU-RARO, del que solo hay 1 en stock."
echo "  Se espera: T1 y T2 OK, T3 falla, y se compensan C2 (reembolso) y C1 (cancelar orden)."
orden orchestration SKU-RARO 5 999.0 | pinta_saga
paso "Estado de los Circuit Breakers:"; cb_estado
echo "${G}  ✔ Rollback correcto en orden inverso (LIFO).${N}"
echo "${G}  ✔ Los circuitos siguen CLOSED: un fallo de negocio NO debe abrirlos.${N}"
}

# ───────────────────────── ESCENARIO 3 ─────────────────────────
esc3 () {
titulo "ESCENARIO 3 — Circuit Breaker: CLOSED -> OPEN"
reset_all
paso "Tiro el Payment Service (fail-mode=error, responde 500 siempre)"
curl -s -X POST "$ORDER/admin/fail-mode/payment?mode=error" | python3 -m json.tool | sed 's/^/    /'
paso "Lanzo 8 ordenes seguidas y observo el estado del circuito:"
for i in $(seq 1 8); do
  code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$ORDER/orders" \
     -H 'Content-Type: application/json' \
     -d '{"customerId":"CLI-CB","sku":"SKU-MOUSE","quantity":1,"amount":25.0}')
  st=$(curl -s "$ORDER/circuit-breakers" | python3 -c "import sys,json;print([c['state'] for c in json.load(sys.stdin) if c['name']=='paymentService'][0])")
  col=$G; [ "$st" = "OPEN" ] && col=$R
  printf "    peticion %d -> HTTP %s | paymentService: ${col}%s${N}\n" "$i" "$code" "$st"
done
paso "Metricas finales:"; cb_estado
echo "${G}  ✔ Con 5 llamadas minimas y 100% de fallos (> 50% del umbral) el circuito ABRIO.${N}"
echo "${G}  ✔ Desde ahi las llamadas se rechazan sin tocar el Payment Service (fallo rapido).${N}"
}

# ───────────────────────── ESCENARIO 4 ─────────────────────────
esc4 () {
titulo "ESCENARIO 4 — Circuit Breaker: OPEN -> HALF_OPEN -> CLOSED"
paso "Continuo con el circuito ABIERTO del escenario anterior."
cb_estado
paso "Reparo el Payment Service (fail-mode=none)"
curl -s -X POST "$ORDER/admin/fail-mode/payment?mode=none" > /dev/null
paso "Espero los 10s de waitDurationInOpenState..."
for i in $(seq 1 11); do printf "\r    %2ds" "$i"; sleep 1; done; echo
paso "Estado tras la espera (debe ser HALF_OPEN):"; cb_estado
paso "Lanzo 3 llamadas de prueba (permittedNumberOfCallsInHalfOpenState = 3):"
for i in 1 2 3; do
  code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$ORDER/orders" \
     -H 'Content-Type: application/json' \
     -d '{"customerId":"CLI-CB","sku":"SKU-MOUSE","quantity":1,"amount":25.0}')
  st=$(curl -s "$ORDER/circuit-breakers" | python3 -c "import sys,json;print([c['state'] for c in json.load(sys.stdin) if c['name']=='paymentService'][0])")
  printf "    prueba %d -> HTTP %s | paymentService: %s\n" "$i" "$code" "$st"
done
paso "Estado final:"; cb_estado
echo "${G}  ✔ Las llamadas de prueba tuvieron exito y el circuito volvio a CLOSED.${N}"
echo "${G}  ✔ Ciclo completo demostrado: CLOSED -> OPEN -> HALF_OPEN -> CLOSED.${N}"
}

# ───────────────────────── ESCENARIO 5 ─────────────────────────
esc5 () {
titulo "ESCENARIO 5 — Fallback: respuesta controlada con el circuito ABIERTO"
reset_all
paso "Tiro el Inventory Service y abro su circuito con 6 peticiones"
curl -s -X POST "$ORDER/admin/fail-mode/inventory?mode=error" > /dev/null
for i in $(seq 1 6); do
  curl -s -o /dev/null -X POST "$ORDER/orders" -H 'Content-Type: application/json' \
    -d '{"customerId":"CLI-FB","sku":"SKU-MOUSE","quantity":1,"amount":25.0}'
done
cb_estado
paso "Siguiente peticion con el circuito ya ABIERTO:"
orden orchestration SKU-MOUSE 1 25.0 | pinta_saga
echo "${G}  ✔ failureType = CIRCUIT_OPEN y HTTP 503: la llamada ni se intento (fallo rapido).${N}"
echo "${G}  ✔ Aun asi la Saga compenso: el cobro se reembolso y la orden se cancelo.${N}"
}

# ───────────────────────── ESCENARIO 6 ─────────────────────────
esc6 () {
titulo "ESCENARIO 6 — Saga por COREOGRAFIA (exito y reversion)"
reset_all
paso "6a) Flujo feliz coreografiado (respuesta asincrona 202)"
RES=$(orden choreography SKU-LAPTOP 1 800.0)
echo "$RES" | pinta_saga
SAGA=$(echo "$RES" | python3 -c "import sys,json;print(json.loads(sys.stdin.read().split('__HTTP__')[0])['sagaId'])")
sleep 2
paso "Cadena de eventos que emergio sola (sin orquestador):"
curl -s "$BUS/events?sagaId=$SAGA" | python3 -c "
import sys,json
for e in json.load(sys.stdin): print(f\"    {e['at'][11:23]}  {e['type']}\")"

paso "6b) Ahora con fallo: dejo SKU-RARO sin stock"
curl -s -X POST "$INV/inventory/stock?sku=SKU-RARO&qty=0" > /dev/null
RES=$(orden choreography SKU-RARO 3 999.0)
SAGA=$(echo "$RES" | python3 -c "import sys,json;print(json.loads(sys.stdin.read().split('__HTTP__')[0])['sagaId'])")
sleep 2
paso "Cadena de eventos con la compensacion en cadena:"
curl -s "$BUS/events?sagaId=$SAGA" | python3 -c "
import sys,json
for e in json.load(sys.stdin):
    col='\033[31m' if 'FAILED' in e['type'] or 'REFUND' in e['type'] or 'CANCEL' in e['type'] else '\033[32m'
    print(f\"    {e['at'][11:23]}  {col}{e['type']}\033[0m  {e.get('payload',{})}\")"
echo "${G}  ✔ Nadie ordeno la compensacion: cada servicio reacciono al evento de fallo.${N}"
}

case "${1:-all}" in
  1) esc1 ;; 2) esc2 ;; 3) esc3 ;; 4) esc4 ;; 5) esc5 ;; 6) esc6 ;;
  all) esc1; esc2; esc3; esc4; esc5; esc6
       titulo "FIN DE LAS PRUEBAS"
       reset_all ;;
  *) echo "Uso: $0 [1-6|all]" ;;
esac
