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
  promotion: string | null;
}

export type EventKind =
  | 'FIRST_MOVER'
  | 'PROMOTION'
  | 'CAPTURE_STANDOFF'
  | 'TURN_ACTION'
  | 'PIECE_SELECTION'
  | 'SQUARE_EVENT';

export interface Duck {
  position: Position;
  turnsRemaining: number;
}

export interface RandomEvent {
  kind: EventKind;
  outcome: string;
  possibleOutcomes: string[];
}

export type TurnAction = 'NORMAL' | 'DOUBLE' | 'SKIP' | 'FORCED' | 'AUTO';

export interface GameState {
  id: string;
  status: GameStatus;
  turn: Color;
  enPassantTarget: Position | null;
  squares: (PieceDto | null)[][];
  whiteJoined: boolean;
  blackJoined: boolean;
  history: MoveDto[];
  lastEvent: RandomEvent | null;
  eventSeq: number;
  currentTurnAction: TurnAction | null;
  movesRemaining: number;
  forcedPiecePosition: Position | null;
  eventSquares: Position[];
  ducks: Duck[];
  pendingSkip: Color | null;
}

export interface JoinResponse {
  gameId: string;
  playerId: string;
  color: Color;
}
