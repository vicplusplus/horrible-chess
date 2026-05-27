export type Color = 'WHITE' | 'BLACK';

export type PieceType = 'PAWN' | 'KNIGHT' | 'BISHOP' | 'ROOK' | 'QUEEN' | 'KING';

export type GameStatus =
  | 'WAITING_FOR_OPPONENT'
  | 'IN_PROGRESS'
  | 'WHITE_WINS'
  | 'BLACK_WINS';

export interface PieceDto {
  type: PieceType;
  color: Color;
  hasMoved: boolean;
}

export interface Position {
  file: number;
  rank: number;
}

export interface MoveDto {
  fromFile: number;
  fromRank: number;
  toFile: number;
  toRank: number;
  piece: PieceType;
  mover: Color;
  captured: PieceType | null;
  promotion: PieceType | null;
}

export interface GameState {
  id: string;
  status: GameStatus;
  turn: Color;
  enPassantTarget: Position | null;
  squares: (PieceDto | null)[][];
  whiteJoined: boolean;
  blackJoined: boolean;
  history: MoveDto[];
}

export interface JoinResponse {
  gameId: string;
  playerId: string;
  color: Color;
}
