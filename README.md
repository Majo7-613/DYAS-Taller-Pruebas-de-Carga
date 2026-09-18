# Pruebas de Carga y Rendimiento — Registraduría

**Entregado por**: María José Almanza Caviedes\
**Materia**: Diseño y Arquitectura de Software

---

## Qué se hizo

Sobre el servicio `registraduria` (Spring Boot, endpoint `POST /register`)
se ejecutaron tres escenarios de carga con k6 (`baseline`, `load` y
`stress`), se validó el resultado de negocio bajo carga, se identificó un
cuello de botella real en la capa de persistencia, se corrigió y se
volvió a medir para confirmar la mejora. El detalle completo —dominio del
sistema, plan de pruebas, resultados con matriz de rendimiento,
conclusiones técnicas, mejoras propuestas y reflexión final— quedó
documentado en la [**Wiki**](../../wiki) de este repositorio; este README
resume lo esencial y cómo reproducirlo.

## El sistema bajo prueba

`registraduria/` expone `POST /register`, que recibe los datos de una
persona (`id`, `name`, `age`, `gender`, `alive`) y aplica reglas de un
registro civil/electoral:

| Resultado | Regla |
|---|---|
| `VALID` | persona viva, mayor de edad (18–120), id nuevo |
| `UNDERAGE` | edad entre 0 y 17 |
| `DEAD` | `alive=false` |
| `INVALID_AGE` | edad negativa o mayor de 120 |
| `DUPLICATED` | el id ya estaba registrado |

Persiste en una base H2 en memoria.

## Estructura del repositorio

```text
.
├─ README.md
├─ defectos.md              # defectos encontrados, con evidencia
├─ registraduria/            # sistema bajo prueba (Spring Boot)
└─ perf/
    ├─ scripts/              # register_person_k6.js, register_voter_k6.js
    ├─ data/                 # persons.csv, voters.csv
    ├─ results/              # resúmenes de cada corrida (no versionados, ver .gitignore)
    └─ ci/                   # plantilla de GitHub Actions (copiada a .github/workflows/)
```

## Plan de pruebas

**Alcance**: `POST /register`, cubierto por dos scripts con propósitos
distintos — `register_person_k6.js` mide rendimiento sobre el camino feliz
(status 200 + cuerpo `VALID`); `register_voter_k6.js` valida que el
resultado de negocio sea el correcto bajo carga, usando 512 filas de
`voters.csv` que cubren las seis clases de equivalencia del dominio.

**SLO/SLA** (definidos como `thresholds` en ambos scripts):

| Métrica | Objetivo |
|---|---|
| p95 latencia | ≤ 300 ms |
| p99 latencia | ≤ 800 ms |
| Error rate | < 1% |
| Resultado de negocio incorrecto | < 1% |

**Ambiente**: máquina local — el servicio y la base H2 corren en el mismo
host que k6, sin latencia de red real ni base de datos remota. Los
tiempos absolutos son bajos por eso; las tendencias relativas entre
escenarios (cómo escala la latencia con la concurrencia, el efecto del
fix) son igualmente válidas. Detalle completo en la página
[Plan-de-pruebas](../../wiki/Plan-de-pruebas) de la Wiki.

## Escenarios ejecutados

| Escenario | `SCENARIO` | VUs | Duración | Para qué |
|---|---|---|---|---|
| Baseline | `baseline` | 20 constantes | 5 min | Línea base estable |
| Carga | `load` | 0→200, rampa | 14 min | Comportamiento en carga esperada |
| Estrés | `stress` | 200→600, rampa | 10 min | Punto de saturación |

`spike`, `soak` y `arrival` están implementados en los scripts pero no se
ejecutaron en esta entrega — ver [Mejoras-propuestas](../../wiki/Mejoras-propuestas).

## Cómo ejecutar

Requisitos: Java 17, Maven, [k6](https://grafana.com/docs/k6/latest/get-started/installation/).

```bash
# 1) Compilar y levantar el servicio (desde registraduria/)
cd registraduria
mvn -DskipTests clean package
java -jar target/registraduria-1.0-SNAPSHOT.jar

# 2) Confirmar que responde (otra terminal)
curl http://localhost:8080/actuator/health

# 3) Correr un escenario (desde la raíz del repositorio)
cd ..
k6 run --env BASE_URL=http://localhost:8080 --env SCENARIO=baseline \
       perf/scripts/register_person_k6.js
```

Escenarios disponibles vía `--env SCENARIO=`: `baseline`, `load`,
`stress`, `spike`, `soak`, `regression` (y `arrival` en
`register_voter_k6.js`). El servicio debe reiniciarse entre corridas: la
base H2 vive mientras vive el proceso, y repetir ids ya registrados
devuelve `DUPLICATED` en vez de `VALID`. Guía completa, con la
automatización usada y la bitácora de tiempos reales de cada corrida, en
[Ejecucion](../../wiki/Ejecucion).

## Resultados

| Escenario | p95 (cliente, k6) | Errores | SLO |
|---|---|---|---|
| Baseline (pre-fix) | 3.00 ms | 0 | Cumple |
| Load (pre-fix) | 103.22 ms | 5 timeouts | Cumple (justo) |
| Stress (pre-fix) | 252.42 ms | 69 (0.0049%) | Cumple (al límite) |
| Baseline (post-fix) | 7.52 ms | 0 | Cumple |
| Load (post-fix) | 34.04 ms | 0 | Cumple (holgado) |

`register_voter_k6.js` (validación de negocio, 57,394 peticiones): 0% de
resultados incorrectos. Matriz completa y comparación cliente/servidor
(Actuator) en [Resultados](../../wiki/Resultados).

## Defecto encontrado y corregido

`RegistryRepository.getConnection()` abría una conexión JDBC nueva por
operación (dos por registro), sin pool. Bajo 200 VUs concurrentes esto se
tradujo en un p95 de 103.22 ms; se corrigió reemplazando
`DriverManager.getConnection()` por un `HikariDataSource`, verificado con
`mvn test` (4/4 pruebas OK). Tras el fix, el p95 de `load` bajó a 34.04 ms
(**-67%**) y el throughput subió **+209%**. Un segundo hallazgo —una
brecha entre lo que mide el cliente y el servidor, causada por el límite
de 200 hilos de Tomcat— quedó identificado pero abierto. Registro
completo, con evidencia, en [`defectos.md`](defectos.md).

## Conclusiones técnicas

El cuello de botella no estaba en el código de negocio ni en la base de
datos en sí, sino en cómo se accedía a ella; solo se hizo visible bajo
concurrencia real, algo que ninguna prueba unitaria ni de integración
puede reproducir. Corregirlo expuso el siguiente límite (el pool de hilos
de Tomcat), un patrón típico al optimizar rendimiento: cada capa que se
arregla revela la que sigue. Análisis completo, con los trade-offs
encontrados, en [Conclusiones-tecnicas](../../wiki/Conclusiones-tecnicas)
y la reflexión final en [Reflexion-final](../../wiki/Reflexion-final).

## Integración continua

`.github/workflows/perf.yml` corre `baseline` y la validación de negocio
en cada Pull Request, y `load`/`stress` completos bajo `workflow_dispatch`
(no en cada PR, porque duran 14 y 10 minutos). El gate es automático: si
el p95 supera el SLO o el error rate pasa de 1%, el `threshold` de k6
falla el paso sin lógica adicional.

## Recursos recomendados

- Apache JMeter (User Manual)
- k6 (docs.k6.io)
- Gatling (gatling.io)
- Google SRE Book – Service Level Objectives
- *Systems Performance* – Brendan Gregg
- *Release It!* – Michael Nygard

---

## Créditos y uso académico

**Autor:** César Augusto Vega Fernández
**Curso:** Testing y Validación de Software
**Programa:** Maestría en Ingeniería de Software – Universidad de La Sabana
**Año:** 2025

Este taller es material académico para el curso *Testing y Validación de Software* y está orientado a fortalecer competencias de **planificación y ejecución de pruebas de rendimiento**, automatización y análisis.

---

## Licencia de uso

Este material se distribuye bajo **CC BY-NC-SA 4.0**. Puedes **usar, adaptar o compartir** con fines educativos, siempre que:

1. Se reconozca la autoría del profesor **César Augusto Vega Fernández**.
2. No se utilice con fines comerciales.
3. Las obras derivadas se distribuyan bajo la misma licencia.
