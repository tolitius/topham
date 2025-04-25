# topham

> "top first Hamming style prioritization": match relevance first, then Hamming weight

* record information with its dimensions
* enrich it with "`topham`": ordered hamming bits
* given an arbitrary set of dimensions, find the best match

[![Clojars Project](https://clojars.org/tolitius/topham/latest-version.svg)](http://clojars.org/tolitius/topham)

- [what is it](#what-is-it)
- [license](#license)

## what is it

**topham matching** is a selection strategy for multi-dimensional data, where each row represents a partially defined configuration.<br/>
some dimensions may be missing (`nil`), and a corresponding bitmask (called `topham`) encodes which fields are present.

a query specifies values for any/some key dimensions, and a row is considered a **match** if all of its defined (non-nil) fields align with the query: priority first, hamming weight second.

### ✨ matching logic

among all matching candidates, topham prioritizes:

 * **alignment first**: a row must match a maximum number of dimensions (compared to other rows) in a query
 * **specificity second**: among matches, the row with the most defined fields (i.e., the highest **hamming weight** in its `topham` bitmask) is selected

this top down, specificity aware approach is ideal for configuration resolution, rule engines, or any system where layered overrides and dimensional fallbacks are needed.

---

### 🛸 example: find a spacecraft

ships has been assigned to missions across the galaxy using dimensions: galaxy, star, planet, moon, and asteroid.

| galaxy      | star       | planet     | moon      | asteroid     | mission-type | `topham` | ship              |
|-------------|------------|------------|-----------|--------------|--------------|----------|-------------------|
| Outer Rim   | Tatoo I    | Tatooine   | -         | -            | Patrol       | 11110    | *Millennium Falcon* |
| Outer Rim   | Tatoo I    | -          | Jedha     | -            | Patrol       | 11010    | *Ghost*           |
| Outer Rim   | -          | Tatooine   | -         | Polis Massa  | Patrol       | 10110    | *TIE Fighter*     |
| -           | Tatoo I    | Tatooine   | -         | -            | Patrol       | 01110    | *X-Wing*          |
| -           | -          | Tatooine   | -         | -            | Patrol       | 00110    | *Starfighter*     |
| -           | -          | -          | -         | -            | Patrol       | 00000    | *Y-Wing*          |

---

🧭 looking for a closest known ship:

```clojure
{:galaxy "Outer Rim", :star "Tatoo I", :planet "Tatooine"}
```
* all its defined fields match the query
* it has the highest specificity (topham = `11110`)
* selected ship: *Millennium Falcon*

but

```clojure
{:asteroid "Polis Massa", :moon "Charon"}
```

would find *TIE Fighter* (topham = `10110`) as a closest known ship<br/>
because out of all the candidates, it has the most defined (1) matching dimensions

## license

Copyright © 2025 tolitius

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
