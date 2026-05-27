import type { Color, PieceType } from './types';

const GLYPHS: Record<Color, Record<PieceType, string>> = {
  WHITE: {
    KING: '♔',
    QUEEN: '♕',
    ROOK: '♖',
    BISHOP: '♗',
    KNIGHT: '♘',
    PAWN: '♙',
  },
  BLACK: {
    KING: '♚',
    QUEEN: '♛',
    ROOK: '♜',
    BISHOP: '♝',
    KNIGHT: '♞',
    PAWN: '♟',
  },
};

export function glyph(color: Color, type: PieceType): string {
  return GLYPHS[color][type];
}
