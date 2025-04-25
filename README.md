# topham

> "top ➡️ down" [hamming weights](https://en.wikipedia.org/wiki/Hamming_weight)

* record information with its dimensions
* enrich it with "`topham`": _ordered_ hamming weight bits
* given an arbitrary set of dimensions, find the best match

[![Clojars Project](https://clojars.org/tolitius/topham/latest-version.svg)](http://clojars.org/tolitius/topham)

- [what is it](#what-is-it)
  - [matching how ✨](#matching-how-)
  - [example: find a spacecraft 🛸](#example-find-a-spacecraft-)
- [license](#license)

## what is it

**topham matching** is a selection strategy for multi dimensional data, where each row represents a partially defined configuration.
some dimensions may be missing, and a corresponding bitmask (called `topham`) encodes which fields are present.

partial dimensions are searched by queries that specify values for any/some key dimensions, and a piece of data is considered a **match** if it maximizes the presence of available dimensions: priority first, hamming weight second.

### matching how ✨

among all matching candidates, topham prioritizes:

 * **alignment first**: a row must match the order/priority as well as a maximum number of dimensions (compared to other rows) in a query
 * **specificity second**: among matches, the row with the most defined fields (i.e., the highest **hamming weight** in its `topham` bitmask) is selected

this top down, specificity aware approach is ideal for configuration resolution, rule engines, or any system where layered overrides and dimensional fallbacks are needed.

### example: find a spacecraft 🛸

ships has been assigned to missions across the galaxy using dimensions: galaxy, star, planet, moon, and asteroid.

| galaxy      | star       | planet     | moon      | asteroid     | mission type | `topham` | ship              |
|-------------|------------|------------|-----------|--------------|--------------|----------|-------------------|
| Outer Rim   | Tatoo I    | Tatooine   | -         | -            | Patrol       | 11110    | **Millennium Falcon** |
| Outer Rim   | Tatoo I    | -          | Jedha     | -            | Patrol       | 11010    | **Ghost**           |
| Outer Rim   | -          | Tatooine   | -         | Polis Massa  | Patrol       | 10110    | **TIE Fighter**     |
| -           | Tatoo I    | Tatooine   | -         | -            | Patrol       | 01110    | **X-Wing**          |
| -           | -          | Tatooine   | -         | -            | Patrol       | 00110    | **Starfighter**     |
| -           | -          | -          | -         | -            | Patrol       | 00000    | **Y-Wing**          |

🧭 looking for a closest known ship:

```clojure
{:galaxy "Outer Rim", :star "Tatoo I", :planet "Tatooine"}
```
* selected ship: **Millennium Falcon**
* _all_ its defined fields match the query
* since it has the highest "presence" (topham = `11110`)

but

```clojure
{:asteroid "Polis Massa", :moon "Charon"}
```
* selected ship: **TIE Fighter** (closest known ship)
* _some_ its defined fields match the query
* since it has the highest "presence" (topham = `10110`)
* and out of all the candidates, it has the most defined (1) matching dimensions

## license

Copyright © 2025 tolitius

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
