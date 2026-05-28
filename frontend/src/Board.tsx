import { useState } from 'react';
import { glyph } from './pieces';
import type { Color, GameState, LegalMove } from './types';

interface Props {
  state: GameState;
  myColor: Color | null;
  interactive: boolean;
  onMove: (
    fromFile: number,
    fromRank: number,
    toFile: number,
    toRank: number
  ) => void;
}

const FILES = ['a', 'b', 'c', 'd', 'e', 'f', 'g', 'h'];

export function Board({ state, myColor, interactive, onMove }: Props) {
  const [selected, setSelected] = useState<{ file: number; rank: number } | null>(null);

  const flipped = myColor === 'BLACK';
  const rankOrder = flipped ? [0, 1, 2, 3, 4, 5, 6, 7] : [7, 6, 5, 4, 3, 2, 1, 0];
  const fileOrder = flipped ? [7, 6, 5, 4, 3, 2, 1, 0] : [0, 1, 2, 3, 4, 5, 6, 7];

  const forced = state.forcedPiecePosition;
  const isForcedSquare = (f: number, r: number) =>
    forced != null && forced.file === f && forced.rank === r;
  const isEventSquare = (f: number, r: number) =>
    state.eventSquares.some((p) => p.file === f && p.rank === r);
  const duckAt = (f: number, r: number) =>
    state.ducks.find((d) => d.position.file === f && d.position.rank === r);

  const movesFromSelected: LegalMove[] = selected
    ? state.legalMoves.filter(
        (m) => m.fromFile === selected.file && m.fromRank === selected.rank
      )
    : [];

  // For each legal move, the square to highlight. Castling moves (king sliding
  // 2+ files) get rewritten to highlight the rook the king is castling with,
  // since the king's actual destination depends on rook placement and is
  // confusing on randomized back rows.
  const highlights = new Map<string, 'move' | 'capture' | 'castle'>();
  for (const m of movesFromSelected) {
    const movingPiece = state.squares[m.fromFile][m.fromRank];
    let f = m.toFile;
    let r = m.toRank;
    let kind: 'move' | 'capture' | 'castle' = 'move';
    if (movingPiece?.type === 'KING' && Math.abs(m.toFile - m.fromFile) >= 2) {
      const dir = Math.sign(m.toFile - m.fromFile);
      f = m.toFile + dir;
      kind = 'castle';
    } else if (state.squares[f][r]) {
      kind = 'capture';
    }
    highlights.set(`${f},${r}`, kind);
  }

  function castleTargetFromRook(rookFile: number, rookRank: number): LegalMove | null {
    if (!selected) return null;
    const king = state.squares[selected.file][selected.rank];
    if (king?.type !== 'KING') return null;
    if (rookRank !== selected.rank) return null;
    const dir = Math.sign(rookFile - selected.file);
    if (dir === 0) return null;
    const kingDest = rookFile - dir;
    return (
      movesFromSelected.find(
        (m) => m.toFile === kingDest && m.toRank === rookRank
      ) ?? null
    );
  }

  function onSquareClick(file: number, rank: number) {
    if (!interactive) return;
    if (state.status !== 'IN_PROGRESS') return;
    if (myColor !== state.turn) return;
    const piece = state.squares[file][rank];
    if (selected) {
      if (selected.file === file && selected.rank === rank) {
        setSelected(null);
        return;
      }
      // Click on own rook with king selected: castle if legal.
      if (piece && piece.color === myColor && piece.type === 'ROOK') {
        const castle = castleTargetFromRook(file, rank);
        if (castle) {
          onMove(selected.file, selected.rank, castle.toFile, castle.toRank);
          setSelected(null);
          return;
        }
      }
      const moving = state.squares[selected.file][selected.rank];
      if (moving && moving.color === myColor) {
        onMove(selected.file, selected.rank, file, rank);
        setSelected(null);
        return;
      }
      setSelected(null);
    }
    if (piece && piece.color === myColor) {
      // If a forced piece is set, only it can be selected.
      if (forced && !isForcedSquare(file, rank)) return;
      setSelected({ file, rank });
    }
  }

  // Glow only when the player can actually move now — `interactive` is already
  // false during a spinner / SKIP / AUTO, so the cue never appears before input
  // is allowed.
  return (
    <div className={'board-wrap' + (interactive ? ' my-turn' : '')}>
      <div className="board">
        {rankOrder.map((rank) => (
          <div className="board-row" key={rank}>
            {fileOrder.map((file) => {
              const piece = state.squares[file][rank];
              const isSelected = selected?.file === file && selected?.rank === rank;
              const light = (file + rank) % 2 === 1;
              const event = isEventSquare(file, rank);
              const duck = duckAt(file, rank);
              const highlight = highlights.get(`${file},${rank}`);
              return (
                <div
                  key={file}
                  className={
                    'square ' +
                    (light ? 'light' : 'dark') +
                    (isSelected ? ' selected' : '') +
                    (isForcedSquare(file, rank) ? ' forced' : '') +
                    (event ? ' event' : '') +
                    (duck ? ' duck' : '') +
                    (highlight ? ` hl-${highlight}` : '')
                  }
                  onClick={() => onSquareClick(file, rank)}
                >
                  {piece && (
                    <span className={'piece ' + piece.color.toLowerCase()}>
                      {glyph(piece.color, piece.type)}
                    </span>
                  )}
                  {!piece && event && <span className="event-marker">?</span>}
                  {highlight === 'move' && !piece && <span className="move-dot" />}
                  {highlight === 'capture' && <span className="capture-ring" />}
                  {highlight === 'castle' && <span className="castle-ring" />}
                  {duck && (
                    <span className="duck-marker" title={`Blocks for ${duck.turnsRemaining} more turn${duck.turnsRemaining === 1 ? '' : 's'}`}>
                      🦆
                    </span>
                  )}
                  {file === fileOrder[0] && (
                    <span className="rank-label">{rank + 1}</span>
                  )}
                  {rank === rankOrder[rankOrder.length - 1] && (
                    <span className="file-label">{FILES[file]}</span>
                  )}
                </div>
              );
            })}
          </div>
        ))}
      </div>
    </div>
  );
}
