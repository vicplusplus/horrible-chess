import type { Color, PieceType } from './types';

// Use the solid (filled) glyph silhouettes for both sides; colour is applied
// via CSS (.piece.white / .piece.black). The outline "white" Unicode glyphs
// (♔♕♖…) render hollow, which read as semi-transparent next to the filled
// black ones — using one filled set keeps both colours equally opaque.
const GLYPHS: Record<PieceType, string> = {
  KING: '♚',
  QUEEN: '♛',
  ROOK: '♜',
  BISHOP: '♝',
  KNIGHT: '♞',
  PAWN: '♟',
};

export function glyph(_color: Color, type: PieceType): string {
  return GLYPHS[type];
}
