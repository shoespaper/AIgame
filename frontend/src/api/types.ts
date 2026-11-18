export type Lifecycle = 'INITIALIZED' | 'ACTIVE' | 'PAUSED' | 'COMPLETED'

export interface WorldState {
  worldId: string
  contentVersion: string
  revision: number
  setting: string | null
  settingDescription: string | null
  location: string | null
  currentRegion: string | null
  mainQuest: string | null
  day: number
  time: string | null
  season: string | null
  weather: string | null
  flags: Record<string, boolean>
  threatLevel: string
  threatCounter: number
  threatInterval: number
  threatHistory: string[]
  timedEvents: string[]
  incidentalEvents: string[]
  lastTickDay: number
  crisisTriggered: boolean
  mainPlotStage: number
  mainPlotStages: string[]
  priceModifiers: Record<string, number>
  originScene: string
}

export interface Item {
  name: string
  type: string
  damage: string
  quantity: number
}

export interface ApState {
  characterName: string
  age: number
  gender: string
  archetype: string
  difficulty: string
  level: number
  xp: number
  xpToNext: number
  strength: number
  dexterity: number
  constitution: number
  intelligence: number
  wisdom: number
  charisma: number
  hpCurrent: number
  hpMax: number
  spCurrent: number
  spMax: number
  inventory: Item[]
  equipment: { weapon: string; armor: string }
  resources: { copper: number; rations: number; potions: number }
  uniqueTalent: { type: string; name: string; description: string; awakened: boolean } | null
}

export interface GameInstanceResponse {
  gameId: string
  worldId: string
  contentVersion: string
  rulesetId: string
  rulesetVersion: string
  lifecycle: Lifecycle
  revision: number
  world: WorldState
  rulesetState: ApState | null
}

export interface InitializePayload {
  worldId: string
  name: string | null
  description: string | null
  location: string | null
  region: string | null
  weather: string | null
  season: string | null
  time: string | null
  mainQuest: string | null
  originScene: string | null
  characterName: string
  age: number
  gender: string
  archetype: string
  difficulty: string
}

export interface ApiErrorPayload { error?: string }
