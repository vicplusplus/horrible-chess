# Horrible Chess

Chess, but every rule is fighting for its life. The board is mostly chess;
the dial gradually goes up to "all RNG, all the time".

## Stack

- **Backend:** Spring Boot 3, Java 21, WebSocket (STOMP). In-memory game store.
- **Frontend:** Vite + React + TypeScript.

## Run locally

Terminal 1 — backend:

```sh
cd backend
mvn spring-boot:run
```

Terminal 2 — frontend:

```sh
cd frontend
npm install
npm run dev
```

Open <http://localhost:5173>. Click **Create new game** to get a game ID, share
the resulting URL (`#/game/<id>`) with the other player. The opponent joining
the link automatically takes the second seat.

## API

| Method | Path                          | Purpose                                |
| ------ | ----------------------------- | -------------------------------------- |
| POST   | `/api/games`                  | Create a game, returns white's session |
| POST   | `/api/games/{id}/join`        | Join a game, returns black's session   |
| GET    | `/api/games/{id}`             | Fetch full state                       |
| POST   | `/api/games/{id}/move`        | Submit a move (validated server-side)  |
| WS     | `/ws` → `/topic/game/{id}`    | Subscribe to live state updates        |

## Variant rules

- **No check / checkmate / stalemate.** A king behaves like a normal piece for
  movement — you can move into "check," and the opponent has to actually
  capture the king to make progress. Castling is allowed through and out of
  attacked squares for the same reason.
- **Last-king-standing wins.** You lose only when *all* of your kings are
  captured. Most positions still start with one king per side, so it usually
  reduces to "capture the king" — but read on.
- **Random promotion** (the first RNG layer). When a pawn reaches the last
  rank, the server rolls a six-sided spinner: Knight, Bishop, Rook, Queen,
  King, or **Failed**.
  - Land on a piece type → pawn becomes that piece. Yes, you can promote to a
    King. That gives you an extra life under the last-king-standing rule.
  - Land on Failed → pawn returns to its origin square. Any piece captured on
    the promotion move stays captured (the move doesn't get undone, only the
    promotion itself fails). The turn is consumed.
