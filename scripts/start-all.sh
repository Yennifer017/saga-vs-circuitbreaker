#!/usr/bin/env bash
# Levanta los 5 procesos del PoC en segundo plano y espera a que esten sanos.
set -u
cd "$(dirname "$0")/.."
mkdir -p logs

start () {  # $1=modulo  $2=puerto
  echo "  levantando $1 en el puerto $2..."
  nohup java -jar "$1/target/$1-1.0.0.jar" > "logs/$1.log" 2>&1 &
  echo $! > "logs/$1.pid"
}

echo "== Levantando el PoC Saga + Circuit Breaker =="
start event-bus         8080
start payment-service   8082
start inventory-service 8083
start shipping-service  8084
start order-service     8081

echo -n "  esperando a que los servicios respondan"
for i in $(seq 1 60); do
  ok=0
  for p in 8080 8081 8082 8083 8084; do
    curl -s -m 1 "http://localhost:$p/actuator/health" | grep -q '"status":"UP"' && ok=$((ok+1))
  done
  [ "$ok" -eq 5 ] && { echo " listo."; break; }
  echo -n "."; sleep 1
done
echo
echo "  Event Bus         http://localhost:8080"
echo "  Order Service     http://localhost:8081   <- API publica"
echo "  Payment Service   http://localhost:8082"
echo "  Inventory Service http://localhost:8083"
echo "  Shipping Service  http://localhost:8084"
echo
echo "Logs en ./logs/*.log   ·   Detener con ./scripts/stop-all.sh"
