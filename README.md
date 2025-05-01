# topham

> "top ➡️ down" [hamming weights](https://en.wikipedia.org/wiki/Hamming_weight)

* record information with its dimensions
* enrich it with "`topham`": _ordered_ hamming weight bits
* given an arbitrary set of dimensions, find the best match

[![Clojars Project](https://img.shields.io/clojars/v/com.tolitius/topham.svg)](https://clojars.org/com.tolitius/topham)

- [what is it](#what-is-it)
  - [matching how ✨](#matching-how-)
  - [example: find a spacecraft 🛸](#example-find-a-spacecraft-)
- [repl it](#repl-it)
- [license](#license)

## what is it

**topham matching** is a selection strategy for multi dimensional data, where each row represents a partially defined configuration.
some dimensions may be missing, and a corresponding bitmask (called `topham`) encodes which fields are present.

partial dimensions are searched by queries that specify values for any/some key dimensions, and a piece of data is considered a **match** if it maximizes the presence of available dimensions: priority first, hamming weight second.

### matching how ✨

among all matching candidates, topham prioritizes:

 * **alignment first**: a row must match the order/priority as well as a maximum number of dimensions (compared to other rows) that _are in a query_
 * **specificity second**: among matches, the row with the most defined fields (i.e., the highest **hamming weight** in its `topham` bitmask) is selected

this top down, specificity aware approach is ideal for configuration resolution, rule engines, or any system where layered overrides and dimensional fallbacks are needed.

### example: find a spacecraft 🛸

ships has been assigned to missions across the galaxy using dimensions: galaxy, star, planet, moon, and asteroid.

| galaxy      | star       | planet     | moon      | asteroid     | mission type | `topham` | ship              |
|-------------|------------|------------|-----------|--------------|--------------|----------|-------------------|
| Outer Rim   | Tatoo I    | Tatooine   | -         | -            | Patrol       | 11100    | **Millennium Falcon** |
| Outer Rim   | Tatoo I    | -          | Jedha     | -            | Patrol       | 11010    | **Ghost**             |
| Outer Rim   | -          | Tatooine   | -         | Polis Massa  | Patrol       | 10101    | **Slave I**           |
| -           | Tatoo I    | Tatooine   | -         | -            | Patrol       | 01100    | **X-Wing**            |
| -           | -          | Tatooine   | -         | -            | Patrol       | 00100    | **Starfighter**       |
| Core        | -          | -          | Endor     | -            | Patrol       | 10010    | **Y-Wing**            |
| Core        | -          | -          | -         | -            | Patrol       | 10000    | **TIE Fighter**       |

🧭 looking for a closest known ship:

```clojure
{:galaxy "Outer Rim" :star "Tatoo I" :planet "Tatooine"}
```
it matches:
| galaxy      | star       | planet     | moon      | asteroid     | mission type | `topham` | ship              |
|-------------|------------|------------|-----------|--------------|--------------|----------|-------------------|
| Outer Rim   | Tatoo I    | Tatooine   | -         | -            | Patrol       | 11100    | **Millennium Falcon** |
| -           | Tatoo I    | Tatooine   | -         | -            | Patrol       | 01100    | **X-Wing**            |
| -           | -          | Tatooine   | -         | -            | Patrol       | 00100    | **Starfighter**       |

* selected ship: **Millennium Falcon**
* since it has the highest "presence" (topham = `11110`)
* _all_ its defined fields match the query

but, what if we don't know the planet?

```clojure
{:galaxy "Core" :star "Alderaan" :moon "Endor"}
```
it matches both:
| galaxy      | star       | planet     | moon      | asteroid     | mission type | `topham` | ship              |
|-------------|------------|------------|-----------|--------------|--------------|----------|-------------------|
| Core        | -          | -          | Endor     | -            | Patrol       | 10010    | **Y-Wing**            |
| Core        | -          | -          | -         | -            | Patrol       | 10000    | **TIE Fighter**       |

* selected ship: **Y-Wing** (closest known ship)
* since has the highest "presence" (topham = `10010`)
* _some_ its defined fields match the query
* e.g. out of all the candidates, it has the most defined (2) matching dimensions

from this examples you can see that "`-`" ("null"/"nothing" in the database) serves as a `wildcard`

wildcards are used two ways:

* when searching for a value that does not exist in the database
* when matching a column value that is not given in the query

in this next example
both a "moon" and an "asteroid" are not given in a query, and hence would match a "nothing" value (a wildcard)<br/>
a star is given, but is not present in the database, and hence a wildcard is used:

```clojure
{:galaxy "Core" :star "Alderaan"}
```
it only matches:
| galaxy      | star       | planet     | moon      | asteroid     | mission type | `topham` | ship              |
|-------------|------------|------------|-----------|--------------|--------------|----------|-------------------|
| Core        | -          | -          | -         | -            | Patrol       | 10000    | **TIE Fighter**       |

* selected ship: **TIE Fighter** (closest known ship)
* since this is the only row with matching galaxy and wildcards for star, moon and asteroid
* _some_ its defined fields match the query
* its topham / "presence" is `10000`
* and out of all the candidates, it has the most defined (1) matching dimensions

## REPL it

export local postgess intel:

```bash
$ env | grep CONN                                                                                                                                            (master ✔ )
DB__CONNECTION__HOST=localhost
DB__CONNECTION__PORT=5432
DB__CONNECTION__USER=topham
DB__CONNECTION__PASSWORD=<secret>
DB__CONNECTION__DATABASE=hamming
```

```
$ make repl
```

```clojure
dev=> (restart)
;; => INFO  com.zaxxer.hikari.HikariDataSource - topham-pool - Starting...
;; => INFO  com.zaxxer.hikari.pool.HikariPool - topham-pool - Added connection org.postgresql.jdbc.PgConnection@2552cb80
;; => INFO  com.zaxxer.hikari.HikariDataSource - topham-pool - Start completed.
;; => {:started ["#'dev/config" "#'dev/datasource"]}
```

```clojure
=> (make-universe! datasource)
;; => universe created. starships ready to rock.

=> (show-missions datasource)
;; => -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
;; => | ID   | Galaxy       | Star       | Planet     | Moon       | Asteroid    | Mission Type | Topham         | Ship               | Payload                                           |
;; => -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
;; => | 1    | Outer Rim    | Tatoo I    | Tatooine   | Jedha      | Polis Massa | Patrol       | 11111 (31)     | Millennium Falcon  | {}                                                |
;; => | 2    | Outer Rim    | Tatoo I    | Tatooine   | Jedha      | -           | Patrol       | 11110 (30)     | Slave I            | {}                                                |
;; => | 3    | Outer Rim    | Tatoo I    | Tatooine   | -          | -           | Patrol       | 11100 (28)     | X-Wing             | {}                                                |
;; => | 4    | Outer Rim    | -          | Tatooine   | -          | -           | Patrol       | 10100 (20)     | Starfighter        | {}                                                |
;; => | 5    | Core         | Alderaan   | -          | Endor      | -           | Recon        | 11010 (26)     | Ghost              | {}                                                |
;; => | 6    | -            | -          | -          | -          | -           | Patrol       | 00000 (0)      | Y-Wing             | {}                                                |
;; => | 7    | Outer Rim    | Tatoo I    | Scarif     | -          | -           | Patrol       | 11100 (28)     | Interceptor        | {}                                                |
;; => | 8    | Outer Rim    | Tatoo I    | Scarif     | -          | -           | Patrol       | 11100 (28)     | TIE Fighter        | {}                                                |
;; => | 9    | -            | -          | Hoth       | -          | -           | Patrol       | 00100 (4)      | Probe Droid        | {}                                                |
;; => | 10   | -            | -          | Hoth       | Echo Base  | -           | Patrol       | 00110 (6)      | Snowspeeder        | {"id": "P42", "crew": 2, "duration": 7, "priori...|
;; => | 11   | -            | Ilum       | Hoth       | Echo Base  | -           | Patrol       | 01110 (14)     | Tauntaun           | {}                                                |
;; => | 12   | Unknown      | -          | -          | -          | -           | Patrol       | 10000 (16)     | Mysterious Shuttle | {}                                                |
;; => | 13   | -            | -          | -          | -          | -           | Patrol       | 00000 (0)      | Truly Generic      | {}                                                |
;; => -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
```

```clojure
dev=> (find-ship datasource {:planet "Hoth" :moon "Echo Base" :mission-type "Patrol"})

;; => looking for a ship closest to: {:planet "Hoth", :moon "Echo Base", :mission-type "Patrol"}

;; => found closest ship:
;; => --------------------------------------------------------------------------------------------------------------------------------------------------------------------
;; => | Galaxy       | Star       | Planet     | Moon       | Asteroid   | Mission Type | Topham         | Ship               | Payload                                  |
;; => --------------------------------------------------------------------------------------------------------------------------------------------------------------------
;; => | -            | -          | Hoth       | Echo Base  | -          | -            | 00110 (6)      | Snowspeeder        | {"id": "P42", "crew": 2, "duration": ... |
;; => --------------------------------------------------------------------------------------------------------------------------------------------------------------------
;; => dimensions matched: [:planet :moon]
```

## license

Copyright © 2025 tolitius

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
