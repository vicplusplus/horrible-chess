import { useState } from 'react';
import { glyph } from './pieces';
import type { Color, GameState } from './types';

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
      const moving = state.squares[selected.file][selected.rank];
      if (moving && moving.color === myColor) {
        onMove(selected.file, selected.rank, file, rank);
        setSelected(null);
        return;
      }
      setSelected(null);
    }
    if (piece && piece.color === myColor) {
      setSelected({ file, rank });
    }
  }

  return (
    <div className="board-wrap">
      <div className="board">
        {rankOrder.map((rank) => (
          <div className="board-row" key={rank}>
            {fileOrder.map((file) => {
              const piece = state.squares[file][rank];
              const isSelected = selected?.file === file && selected?.rank === rank;
              const light = (file + rank) % 2 === 1;
              return (
                <div
                  key={file}
                  className={
                    'square ' +
                    (light ? 'light' : 'dark') +
                    (isSelected ? ' selected' : '')
                  }
                  onClick={() => onSquareClick(file, rank)}
                >
                  {piece && (
                    <span className={'piece ' + piece.color.toLowerCase()}>
                      {glyph(piece.color, piece.type)}
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
