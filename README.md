# Horrible Chess

Chess, but every rule is fighting for its life. Currently: standard chess where
the only way to win is to actually capture the king (no check/checkmate/stalemate).
RNG mechanics to come.

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

## Variant rule (the only one, for now)

There is no concept of check, checkmate, or stalemate. A king behaves like a
normal piece for the purposes of movement — you can move into "check," and the
opponent has to actually capture the king on their next turn to win. Castling
is allowed through and out of attacked squares for the same reason.
