# Registro de Defectos

Curso: Testing y Validación de Software
Proyecto: Pruebas de Carga y Rendimiento
Fecha: 2026-09-17

------------------------------------------------------------------------

## Introducción

Este documento recopila los defectos identificados durante la ejecución real
de las pruebas de rendimiento (`baseline`, `load`, `stress`) sobre
`registraduria` corriendo en `http://localhost:8080`, usando
`perf/scripts/register_person_k6.js` y `perf/scripts/register_voter_k6.js`
tal como se describe en el README. Todas las cifras de este documento
provienen de corridas reales (los JSON completos están en `perf/results/`),
no son ilustrativas.

**Entorno de medición**: máquina local (Windows), servicio y base de datos
H2 en memoria en el mismo host que k6. Esto significa que no hay latencia de
red real ni una base de datos remota: los tiempos absolutos son mucho más
bajos que en un ambiente productivo, pero las *tendencias* entre escenarios
(baseline vs load vs stress, antes vs después del fix) siguen siendo válidas
y son el foco de este análisis.

------------------------------------------------------------------------

## Formato 1: Lista detallada

## Defecto PERF-01 — RegistryRepository sin pool de conexiones JDBC

- **Capa afectada**: Persistencia (`RegistryRepository.getConnection()`)
- **Caso de prueba**: `register_person_k6.js`, escenarios `load` (200 VUs) y
  `stress` (600 VUs) contra `POST /register`
- **Entrada**: peticiones concurrentes con datos de `perf/data/persons.csv`
  (ids únicos por VU/iteración)
- **Resultado esperado**: latencia estable al subir la concurrencia; SLO del
  script (`p95<300ms`, `p99<800ms`, error rate `<1%`)
- **Resultado obtenido (antes del fix)**: el p95 sube de forma pronunciada
  con la concurrencia, y aparecen timeouts en el arranque de `load`:

| Escenario | VUs | Peticiones | p95 (cliente, k6) | Errores |
|---|---|---|---|---|
| baseline | 20 | 2,323,638 | 3.00 ms | 0 |
| load | 200 | 1,978,264 | 103.22 ms | 5 timeouts |
| stress | 600 | 1,412,093 | 252.42 ms | 69 (0.0049%) |

  El p95 sube **~34x** de baseline a load, y **~84x** de baseline a stress,
  aunque técnicamente el SLO de 300ms seguía sin romperse en este entorno
  local. `jvm.threads.live` (Actuator) confirma la causa: se satura en
  **212 hilos** (el `max-threads` por defecto de Tomcat) tanto con 200 como
  con 600 VUs — con más peticiones que hilos disponibles, el exceso hace
  cola, y cada conexión JDBC nueva que se abre para atenderlas agrava esa
  cola.

- **Causa probable**: `RegistryRepository.getConnection()` llamaba a
  `DriverManager.getConnection(jdbcUrl, username, password)` en cada
  operación (`existsById` y `save`, es decir **dos conexiones nuevas por
  registro**), sin ningún pool. Bajo concurrencia, crear y destruir
  conexiones compite por los mismos recursos que atienden las peticiones.
- **Evidencia**: `perf/results/summary-baseline-prefix.json`,
  `summary-load-prefix.json`, `summary-stress.json`,
  `actuator-threads-person-load.json`, `actuator-threads-person-stress.json`
- **Corrección aplicada**: se reemplazó `DriverManager.getConnection()` por
  un `HikariDataSource` (pool `maximumPoolSize=20`, `minimumIdle=5`) dentro
  de `RegistryRepository`, sin cambiar su interfaz pública — ver commit
  "Agrega pool de conexiones HikariCP en RegistryRepository". Se verificó
  con `mvn test` que las pruebas unitarias e integración siguen pasando.
- **Resultado después del fix** (mismos escenarios, servicio reiniciado):

| Escenario | VUs | Peticiones | p95 (cliente, k6) | Errores |
|---|---|---|---|---|
| baseline | 20 | 1,661,825 | 7.52 ms | 0 |
| load | 200 | 6,113,872 | 34.04 ms | 0 |

  En `load` (donde se concentra la concurrencia): el p95 baja de **103.22ms
  a 34.04ms (-67%)**, el throughput sube de **~2,354 a ~7,278 req/s
  (+209%)**, y los 5 timeouts del arranque desaparecen por completo (0
  errores en 6.1 millones de peticiones). En `baseline` (20 VUs, sin
  contención real) el p95 sube levemente de 3.00ms a 7.52ms: es el costo
  fijo de tomar una conexión prestada del pool (sincronización interna de
  HikariCP), que a baja concurrencia pesa más que el ahorro que ofrece —
  el pool gana donde importa (bajo concurrencia real), no en todos lados.
  Evidencia: `perf/results/summary-baseline.json`, `summary-load.json`
  (post-fix).
- **Estado**: Resuelto (validado en `load`; `stress` no se volvió a correr
  post-fix por tiempo, pero el mecanismo de la mejora — menos contención de
  conexiones — aplica igual o mejor a mayor concurrencia)
- **Prioridad**: Alta

------------------------------------------------------------------------

## Defecto PERF-02 — Brecha cliente/servidor por cola de hilos de Tomcat

- **Capa afectada**: Servidor de aplicación (Tomcat embebido, `max-threads`
  por defecto = 200)
- **Caso de prueba**: escenario `load`, comparando `http_req_duration` que
  reporta k6 (cliente) contra `http.server.requests` que reporta Actuator
  (servidor)
- **Resultado esperado**: si no hay cola de espera, cliente y servidor
  deberían medir una latencia promedio similar
- **Resultado obtenido**:

| Escenario | Promedio cliente (k6) | Promedio servidor (Actuator) | Brecha |
|---|---|---|---|
| baseline (pre-fix) | 2.03 ms | 2.04 ms | ~0 ms |
| load (pre-fix) | 47.38 ms | 2.49 ms | ~44.9 ms |
| stress (pre-fix) | 98.59 ms | 3.63 ms | ~94.9 ms |
| load (post-fix) | 14.44 ms | 0.89 ms | ~13.6 ms |

  En baseline (20 VUs, muy por debajo del límite de 200 hilos) casi no hay
  brecha. En `load` y `stress` (pre-fix) la brecha crece con la
  concurrencia: es tiempo que la petición pasa **esperando en la cola**
  antes de que un hilo la atienda, no tiempo de procesamiento real — el
  servidor ni se entera de que la petición existía todavía. Confirma
  exactamente el punto del README: "si el servidor dice 20ms y el cliente
  dice 400ms, el problema está en la saturación, no en el código de
  negocio". El fix de HikariCP (PERF-01) redujo la brecha de ~44.9ms a
  ~13.6ms porque las peticiones se procesan más rápido y hay menos cola
  acumulada, pero no la eliminó, porque el límite real es el pool de hilos
  de Tomcat, no la capa de datos.
- **Causa probable**: `server.tomcat.max-threads` no está configurado en
  `application.properties` (usa el valor por defecto de Spring Boot, 200);
  con 200+ VUs concurrentes, toda petición que excede ese límite espera en
  cola.
- **Evidencia**: `perf/results/actuator-threads-person-load.json` (212
  hilos pre-fix, 214 post-fix — sin cambios significativos, confirma que
  esta cola es independiente del fix de conexiones), comparación de
  promedios arriba.
- **Estado**: Abierto — es una decisión de capacidad, no un bug: subir
  `max-threads` movería el límite pero no lo elimina; la pregunta real es
  si el servicio necesita sostener esa concurrencia y con qué recursos.
- **Prioridad**: Media

------------------------------------------------------------------------

## Verificación de resultado de negocio (sin defectos encontrados)

`register_voter_k6.js`, escenario `baseline`, 57,394 peticiones cubriendo
las seis clases de equivalencia del dominio (`VALID`, `UNDERAGE`, `DEAD`,
`INVALID_AGE`, incluidos los valores límite de edad 0, 120 y 121):
**`register_failed` = 0%** — las 57,394 respuestas de negocio coincidieron
con lo esperado en `perf/data/voters.csv`. No se encontraron defectos de
lógica de negocio bajo carga. Evidencia: `perf/results/summary-voters-baseline.json`.

------------------------------------------------------------------------

## Nota metodológica

Durante la corrida `baseline` pre-fix, una sola petición (de 2.3 millones)
reportó una duración de ~963.8 segundos, tanto en el cliente (k6) como en
el servidor (Actuator `MAX`). Esto coincide con una pausa real de ~16
minutos observada en el log de esa corrida, consistente con que el equipo
entrara en suspensión durante la prueba. No afecta el p95 (una sola
petición sobre millones) y no es un defecto del sistema bajo prueba, pero
se documenta por transparencia. Recomendación: deshabilitar la suspensión
automática del equipo antes de corridas largas (`load`, `stress`, `soak`).

------------------------------------------------------------------------

## Formato 2: Tabla de seguimiento

| ID | Escenario | Resultado esperado | Resultado obtenido | Estado | Prioridad |
|----|-----------|--------------------|--------------------|--------|-----------|
| PERF-01 | Load (200 VUs) | p95 estable, sin timeouts | p95=103.22ms, 5 timeouts → tras fix: p95=34.04ms, 0 errores | Resuelto | Alta |
| PERF-01 | Stress (600 VUs) | p95 estable bajo saturación | p95=252.42ms, 69 fallos de negocio (0.0049%) | Resuelto (mecanismo validado en load) | Alta |
| PERF-02 | Load: cliente vs servidor | brecha ≈ 0 | brecha de 44.9ms (pre-fix) → 13.6ms (post-fix) | Abierto | Media |

------------------------------------------------------------------------

## Convenciones de Estado

Abierto: Defecto identificado sin corrección aplicada.\
En progreso: En proceso de corrección.\
Resuelto: Corregido y validado con nuevas pruebas.

------------------------------------------------------------------------

Universidad de La Sabana -- Facultad de Ingeniería\
Curso: Testing y Validación de Software (2025-1)
