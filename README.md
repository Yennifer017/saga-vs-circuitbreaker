# PoC — Saga Pattern vs Circuit Breaker Pattern

Prueba de concepto de los dos patrones sobre un flujo de compra de e-commerce con
4 microservicios, implementada con **Java 21 + Spring Boot 3.3 + Resilience4j 2.2**.

Incluye **los dos enfoques de Saga** (orquestación y coreografía) sobre los mismos
servicios, para poder compararlos.

---

## 1. Arquitectura

```
                          ORQUESTACIÓN (síncrona, REST)
                    ┌──────────────────────────────────────┐
                    │        SagaOrchestrator              │
                    │     (dentro de Order Service)        │
                    └───┬──────────┬──────────┬────────────┘
                   CB#1 │     CB#2 │     CB#3 │
                        ▼          ▼          ▼
   ┌──────────┐   ┌──────────┐ ┌──────────┐ ┌──────────┐
   │  Order   │   │ Payment  │ │Inventory │ │ Shipping │
   │   8081   │   │   8082   │ │   8083   │ │   8084   │
   └────┬─────┘   └────┬─────┘ └────┬─────┘ └────┬─────┘
        │              │            │            │
        └──────────────┴────────────┴────────────┘
                       │
                 ┌─────▼──────┐     COREOGRAFÍA (asíncrona, eventos)
                 │ Event Bus  │     cada servicio publica y reacciona
                 │    8080    │     por su cuenta, sin coordinador
                 └────────────┘
```

| Componente        | Puerto | Rol |
|-------------------|--------|-----|
| event-bus         | 8080   | Broker publish/subscribe (infraestructura, no es un servicio de negocio) |
| order-service     | 8081   | API pública + orquestador de la Saga + **los 3 Circuit Breakers** |
| payment-service   | 8082   | Cobro y reembolso |
| inventory-service | 8083   | Reserva y liberación de stock |
| shipping-service  | 8084   | Programación y cancelación de envíos |

---

## 2. Cómo ejecutarlo

Requisitos: **Java 21** y **Maven**.

```bash
mvn -DskipTests package      # compila los 5 servicios
./scripts/start-all.sh       # los levanta y espera a que estén sanos
./scripts/demo.sh all        # ejecuta los 6 escenarios de prueba
./scripts/stop-all.sh        # los detiene
```

Los logs quedan en `logs/*.log`. Para ver la demo paso a paso: `./scripts/demo.sh 3`.

---

## 3. Endpoints

### Transacciones (T) y compensaciones (C)

| Paso | Transacción                       | Compensación                          |
|------|-----------------------------------|---------------------------------------|
| T1   | `POST /orders`                    | `DELETE /orders/{id}`                 |
| T2   | `POST /payments`                  | `POST /payments/{id}/refund`          |
| T3   | `POST /inventory/reserve`         | `POST /inventory/release`             |
| T4   | `POST /shipping/schedule`         | `DELETE /shipping/{id}`               |

### API pública (order-service, puerto 8081)

| Método | Ruta | Descripción |
|--------|------|-------------|
| `POST` | `/orders?mode=orchestration` | Ejecuta la Saga orquestada (respuesta síncrona) |
| `POST` | `/orders?mode=choreography`  | Inicia la Saga coreografiada (respuesta 202, asíncrona) |
| `GET`  | `/orders` · `/orders/{id}`   | Consulta de órdenes |
| `GET`  | `/sagas/{sagaId}/events`     | Traza de eventos de una saga coreografiada |
| `GET`  | `/circuit-breakers`          | **Estado en vivo de los 3 Circuit Breakers** |
| `POST` | `/circuit-breakers/reset`    | Devuelve todos los circuitos a CLOSED |
| `POST` | `/admin/fail-mode/{service}?mode=...` | Simula fallos (ver abajo) |
| `GET`  | `/actuator/health`           | Salud, incluyendo el detalle de cada Circuit Breaker |

### Simulación de fallos

`POST /admin/fail-mode/{payment|inventory|shipping}?mode=X&latencyMs=N`

| mode       | Efecto | Para qué sirve |
|------------|--------|----------------|
| `none`     | Normal | Restaurar |
| `error`    | Responde 500 siempre | Abrir el Circuit Breaker |
| `slow`     | Tarda `latencyMs` | Disparar timeouts y `slowCallRateThreshold` |
| `business` | Rechazo de negocio (409/402) | Revertir la Saga **sin** abrir el circuito |

---

## 4. Configuración de resiliencia

`order-service/src/main/resources/application.yml` — valores exigidos por el enunciado:

```yaml
failureRateThreshold: 50                   # % de fallos para ABRIR
waitDurationInOpenState: 10s               # espera en OPEN antes de HALF_OPEN
slidingWindowSize: 10                      # llamadas consideradas
permittedNumberOfCallsInHalfOpenState: 3   # llamadas de prueba
```

Además se implementan los patrones complementarios: **Retry** (3 intentos),
**Timeout** (2 s de lectura), **Bulkhead** (10 llamadas concurrentes por dependencia)
y **Fallback** (respuesta controlada).

---

## 5. Escenarios de prueba

| # | Escenario | Resultado esperado |
|---|-----------|--------------------|
| 1 | Flujo feliz (orquestación) | `COMPLETED`, HTTP 201, stock descontado |
| 2 | Fallo de negocio en Inventory | `COMPENSATED`, HTTP 409, circuitos **CLOSED** |
| 3 | Payment caído, 8 peticiones | El circuito pasa a **OPEN** en la 5.ª |
| 4 | Payment reparado + espera 10 s | **OPEN → HALF_OPEN → CLOSED** |
| 5 | Petición con el circuito abierto | `CIRCUIT_OPEN`, HTTP 503, llamada no intentada |
| 6 | Coreografía, éxito y reversión | Cadena de eventos con compensación automática |

---

## 6. Estructura del proyecto

```
saga-circuitbreaker-poc/
├── common/            DTOs, simulador de fallos y cliente del Event Bus
├── event-bus/         Broker publish/subscribe (coreografía)
├── order-service/     SagaOrchestrator + DownstreamClient (Circuit Breakers) + API
├── payment-service/   Cobro/reembolso + handler de coreografía
├── inventory-service/ Stock + handler de coreografía
├── shipping-service/  Envíos + handler de coreografía
└── scripts/           start-all.sh · stop-all.sh · demo.sh
```
