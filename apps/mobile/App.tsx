import Constants from 'expo-constants';
import * as Crypto from 'expo-crypto';
import * as ImagePicker from 'expo-image-picker';
import * as Linking from 'expo-linking';
import * as Location from 'expo-location';
import * as SecureStore from 'expo-secure-store';
import { StatusBar } from 'expo-status-bar';
import { getShareIntent, useShareIntent } from 'expo-share-intent';
import type { ComponentProps } from 'react';
import { useCallback, useEffect, useRef, useState } from 'react';
import { Image, Platform, Pressable, ScrollView, StyleSheet, Text, TextInput, View } from 'react-native';

type Screen = 'auth' | 'folders' | 'diaryFeed' | 'diaryComposer' | 'diaryDetail' | 'placesList' | 'placeDetailInbox';
type AuthMode = 'login' | 'signup';
type Session = { token: string; user: UserAccount };
type UserAccount = { userId: string; email: string; displayName: string };
type Folder = { folderId: string; type: string; name: string; description: string; role: string };
type DiaryEntry = {
  entryId: string;
  folderId: string;
  mediaId: string;
  title: string;
  body: string;
  placeName: string;
  latitude: number;
  longitude: number;
  capturedAt: string;
  tags: string[];
  createdAt: string;
};
type MediaAsset = { mediaId: string; gpsLatitude?: number | null; gpsLongitude?: number | null; takenAt?: string | null };
type SavedPlace = {
  placeId: string;
  folderId: string;
  name: string;
  category: string;
  address: string;
  regionText: string;
  latitude?: number | null;
  longitude?: number | null;
  summary: string;
  whyRecommended: string;
  keywords: string[];
  visitStatus: string;
};
type PlaceCandidate = { candidateId: string; name: string; address: string; latitude?: number | null; longitude?: number | null; confidence: number };
type ShareIntake = {
  intakeId: string;
  folderId: string;
  rawUrl: string;
  rawTitle: string;
  rawText: string;
  status: string;
  failureReason?: string | null;
  candidates: PlaceCandidate[];
  resolvedPlace?: SavedPlace | null;
};
type PendingSharePayload = {
  clientIntakeId: string;
  contentFingerprint: string;
  receivedAt: number;
  userId?: string;
  intendedFolderId?: string;
  rawUrl: string;
  rawTitle: string;
  rawText: string;
  sourceApp: string;
  platform: string;
  receivedVia: string;
  lastError?: string;
  retryState?: RetryState;
};
type RetryState = 'retryable' | 'auth' | 'validation' | 'conflict';
type PendingShareMetadata = {
  generation: string;
  count: number;
  checksum: string;
  receipts: Record<string, string>;
};
type CoordinateProvenance = 'exif' | 'current' | 'manual';
type PickedPhoto = { uri: string; name: string; mimeType: string; latitude?: number; longitude?: number; takenAt?: string };

type JsonRecord = Record<string, unknown>;

const developmentBuild = typeof __DEV__ === 'undefined' || __DEV__;
const configuredApiBaseUrl =
  process.env.EXPO_PUBLIC_API_BASE_URL ??
  (Platform.OS === 'android'
    ? (Constants.expoConfig?.extra?.apiBaseUrlAndroidEmulator as string | undefined)
    : (Constants.expoConfig?.extra?.apiBaseUrl as string | undefined));
if (!developmentBuild && !configuredApiBaseUrl) throw new Error('EXPO_PUBLIC_API_BASE_URL is required for release builds.');
const apiBaseUrl = (configuredApiBaseUrl ?? (Platform.OS === 'android' ? 'http://10.0.2.2:8080' : 'http://localhost:8080')).replace(/\/$/, '');
if (!developmentBuild && !apiBaseUrl.startsWith('https://')) throw new Error('Release API URL must use HTTPS.');
const sessionKey = 'picturejournal.session.v1';
const pendingShareKey = 'picturejournal.pendingShares.v3';
const pendingShareChunkByteLimit = 1800;
const pendingShareTtlMs = 24 * 60 * 60 * 1000;
const diaryFolderKey = 'picturejournal.diaryFolder.v1';
const placesFolderKey = 'picturejournal.placesFolder.v1';
const screens: { key: Screen; label: string; product: 'Core' | 'Photo Diary' | 'Saved Places' }[] = [
  { key: 'auth', label: 'Sign in', product: 'Core' },
  { key: 'folders', label: 'Folders', product: 'Core' },
  { key: 'diaryFeed', label: 'Photo Diary', product: 'Photo Diary' },
  { key: 'diaryComposer', label: 'New diary entry', product: 'Photo Diary' },
  { key: 'diaryDetail', label: 'Diary detail', product: 'Photo Diary' },
  { key: 'placesList', label: 'Saved Places', product: 'Saved Places' },
  { key: 'placeDetailInbox', label: 'Place details and inbox', product: 'Saved Places' },
];

function text(value: unknown): string {
  return typeof value === 'string' ? value : '';
}

class ApiError extends Error {
  constructor(readonly status: number, message: string) {
    super(message);
  }
}

function lengthPrefixed(values: string[]): string {
  return values.map((value) => `${new TextEncoder().encode(value).length}:${value}`).join('');
}

export async function createPendingShare(input: {
  rawUrl: string;
  rawTitle: string;
  rawText: string;
  sourceApp: string;
  platform: string;
  receivedVia: string;
}): Promise<PendingSharePayload> {
  const receivedAt = Date.now();
  const canonical = lengthPrefixed([
    input.rawUrl.trim(),
    input.rawTitle.trim(),
    input.rawText.trim(),
    input.sourceApp,
    input.platform,
    input.receivedVia,
  ]);
  const contentFingerprint = await Crypto.digestStringAsync(Crypto.CryptoDigestAlgorithm.SHA256, canonical);
  const clientIntakeId = Crypto.randomUUID();
  return { ...input, receivedAt, contentFingerprint, clientIntakeId };
}

const webStoragePrefix = 'picturejournal.web.v1.';

function getWebStorage() {
  const storage = (globalThis as typeof globalThis & { localStorage?: { getItem: (key: string) => string | null; setItem: (key: string, value: string) => void; removeItem: (key: string) => void } }).localStorage;
  if (!storage) throw new Error('Durable web storage is unavailable.');
  return {
    getItem: (key: string) => storage.getItem(`${webStoragePrefix}${key}`),
    setItem: (key: string, value: string) => storage.setItem(`${webStoragePrefix}${key}`, value),
    removeItem: (key: string) => storage.removeItem(`${webStoragePrefix}${key}`),
  };
}

export async function secureGet(key: string): Promise<string | null> {
  if (Platform.OS === 'web') return getWebStorage().getItem(key);
  return SecureStore.getItemAsync(key);
}

export async function secureSet(key: string, value: string): Promise<void> {
  if (Platform.OS === 'web') {
    getWebStorage().setItem(key, value);
    return;
  }
  await SecureStore.setItemAsync(key, value, { keychainAccessible: SecureStore.AFTER_FIRST_UNLOCK_THIS_DEVICE_ONLY });
}

export async function secureDelete(key: string): Promise<void> {
  if (Platform.OS === 'web') {
    getWebStorage().removeItem(key);
    return;
  }
  await SecureStore.deleteItemAsync(key);
}

export function utf8Chunks(value: string, byteLimit = pendingShareChunkByteLimit): string[] {
  if (!Number.isInteger(byteLimit) || byteLimit < 1) throw new Error('Chunk byte limit must be a positive integer.');
  const encoder = new TextEncoder();
  const chunks: string[] = [];
  let chunk = '';
  let chunkBytes = 0;
  for (const character of value) {
    const characterBytes = encoder.encode(character).length;
    if (characterBytes > byteLimit) throw new Error('A UTF-8 character exceeds the storage chunk limit.');
    if (chunk && chunkBytes + characterBytes > byteLimit) {
      chunks.push(chunk);
      chunk = '';
      chunkBytes = 0;
    }
    chunk += character;
    chunkBytes += characterBytes;
  }
  return chunks.length || chunk ? [...chunks, chunk] : [''];
}

export function utf8Checksum(value: string): string {
  let hash = 0x811c9dc5;
  for (const byte of new TextEncoder().encode(value)) {
    hash ^= byte;
    hash = Math.imul(hash, 0x01000193);
  }
  return (hash >>> 0).toString(16).padStart(8, '0');
}

export function createPendingShareMetadata(generation: string, chunks: string[], receipts: Record<string, string> = {}): PendingShareMetadata {
  if (!generation || !chunks.length) throw new Error('Pending share generation metadata is invalid.');
  return { generation, count: chunks.length, checksum: utf8Checksum(chunks.join('')), receipts };
}

function parsePendingShareMetadata(raw: string): PendingShareMetadata {
  const metadata = JSON.parse(raw) as PendingShareMetadata;
  if (
    typeof metadata.generation !== 'string' ||
    !metadata.generation ||
    !Number.isInteger(metadata.count) ||
    metadata.count < 1 ||
    typeof metadata.checksum !== 'string' ||
    !metadata.checksum ||
    !metadata.receipts ||
    typeof metadata.receipts !== 'object' ||
    Array.isArray(metadata.receipts)
  ) {
    throw new Error('Pending share storage metadata is invalid.');
  }
  return metadata;
}

export async function secureGetChunked(key: string): Promise<{ value: string; metadata: PendingShareMetadata } | null> {
  const rawMetadata = await secureGet(`${key}.meta`);
  if (!rawMetadata) return null;
  const metadata = parsePendingShareMetadata(rawMetadata);
  const chunks = await Promise.all(Array.from({ length: metadata.count }, (_, index) => secureGet(`${key}.${metadata.generation}.${index}`)));
  if (chunks.some((chunk) => chunk === null)) throw new Error('Pending share storage is incomplete.');
  const value = chunks.join('');
  if (utf8Checksum(value) !== metadata.checksum) throw new Error('Pending share storage checksum does not match.');
  return { value, metadata };
}

export async function secureSetChunked(key: string, value: string, receipts: Record<string, string>): Promise<PendingShareMetadata> {
  const previousRawMetadata = await secureGet(`${key}.meta`);
  let previousMetadata: PendingShareMetadata | null = null;
  if (previousRawMetadata) {
    try {
      previousMetadata = parsePendingShareMetadata(previousRawMetadata);
    } catch {
      await secureDelete(`${key}.meta`).catch(() => undefined);
    }
  }
  const generation = `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`;
  const chunks = utf8Chunks(value);
  for (const [index, chunk] of chunks.entries()) await secureSet(`${key}.${generation}.${index}`, chunk);
  const metadata = createPendingShareMetadata(generation, chunks, receipts);
  await secureSet(`${key}.meta`, JSON.stringify(metadata));
  if (previousMetadata) {
    for (let index = 0; index < previousMetadata.count; index += 1) {
      try {
        await secureDelete(`${key}.${previousMetadata.generation}.${index}`);
      } catch {
        // The committed pointer remains valid; a later successful write can clean this obsolete generation.
      }
    }
  }
  return metadata;
}

export async function secureDeleteChunked(key: string): Promise<void> {
  const rawMetadata = await secureGet(`${key}.meta`);
  if (!rawMetadata) return;
  let metadata: PendingShareMetadata | null = null;
  let diagnostic: Error | null = null;
  try {
    metadata = parsePendingShareMetadata(rawMetadata);
  } catch (error) {
    diagnostic = error instanceof Error ? error : new Error('Pending share storage metadata is invalid.');
  }
  try {
    await secureDelete(`${key}.meta`);
  } catch (error) {
    diagnostic = error instanceof Error ? error : new Error('Pending share metadata deletion failed.');
  }
  if (metadata) {
    for (let index = 0; index < metadata.count; index += 1) {
      try {
        await secureDelete(`${key}.${metadata.generation}.${index}`);
      } catch (error) {
        diagnostic = diagnostic ?? (error instanceof Error ? error : new Error('Pending share chunk deletion failed.'));
      }
    }
  }
  if (diagnostic) throw diagnostic;
}

export function parseCoordinate(value: string, label: 'latitude' | 'longitude'): number {
  if (!value.trim()) throw new Error(`Final ${label} is required.`);
  const coordinate = Number(value);
  const maximum = label === 'latitude' ? 90 : 180;
  if (!Number.isFinite(coordinate) || coordinate < -maximum || coordinate > maximum) {
    throw new Error(`Final ${label} must be between ${-maximum} and ${maximum}.`);
  }
  return coordinate;
}

export function normalizeExifCoordinate(value: unknown, direction: unknown, negativeDirection: 'S' | 'W'): number | undefined {
  if (typeof value !== 'number' || !Number.isFinite(value) || value < 0) return undefined;
  const positiveDirection = negativeDirection === 'S' ? 'N' : 'E';
  const normalizedDirection = typeof direction === 'string' ? direction.toUpperCase() : '';
  if (normalizedDirection !== positiveDirection && normalizedDirection !== negativeDirection) return undefined;
  const normalized = normalizedDirection === negativeDirection ? -value : value;
  const maximum = negativeDirection === 'S' ? 90 : 180;
  return normalized <= maximum ? normalized : undefined;
}

export function normalizeExifTakenAt(value: unknown): string | undefined {
  if (typeof value !== 'string') return undefined;
  const normalized = value.trim();
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,3})?(?:Z|[+-]\d{2}:\d{2})$/.test(normalized)) return undefined;
  const parsed = new Date(normalized);
  return Number.isNaN(parsed.getTime()) ? undefined : parsed.toISOString();
}

export function classifyShareRetry(statusCode: number | undefined): RetryState {
  if (statusCode === 401 || statusCode === 403) return 'auth';
  if (statusCode === 409) return 'conflict';
  if (statusCode !== undefined && statusCode >= 400 && statusCode < 500
      && statusCode !== 408 && statusCode !== 425 && statusCode !== 429) return 'validation';
  return 'retryable';
}

export function classifyAuthResponse(statusCode: number | undefined): 'reauthenticate' | 'retry' | 'valid' {
  if (statusCode === 401 || statusCode === 403) return 'reauthenticate';
  if (statusCode !== undefined && statusCode >= 200 && statusCode < 300) return 'valid';
  return 'retry';
}

export function isPendingShareExpired(item: PendingSharePayload, now = Date.now()): boolean {
  return now - item.receivedAt >= pendingShareTtlMs;
}

export function filterFreshPendingShares(items: PendingSharePayload[], now = Date.now()): PendingSharePayload[] {
  return items.filter((item) => !isPendingShareExpired(item, now));
}

export function filterPendingShareReceipts(receipts: Record<string, string>, items: PendingSharePayload[]): Record<string, string> {
  const retainedIds = new Set(items.map((item) => item.clientIntakeId));
  return Object.fromEntries(Object.entries(receipts).filter(([, clientIntakeId]) => retainedIds.has(clientIntakeId)));
}

export function dedupePendingShares(items: PendingSharePayload[]): PendingSharePayload[] {
  const seen = new Set<string>();
  return items.filter((item) => {
    if (!item.clientIntakeId || seen.has(item.clientIntakeId)) return false;
    seen.add(item.clientIntakeId);
    return true;
  });
}

export function parsePendingShares(raw: string): PendingSharePayload[] {
  const parsed: unknown = JSON.parse(raw);
  if (!Array.isArray(parsed)) throw new Error('Pending share storage must contain an array.');
  const allowedRetryStates = new Set<RetryState>(['retryable', 'auth', 'validation', 'conflict']);
  for (const item of parsed) {
    if (
      !item
      || typeof item !== 'object'
      || typeof item.clientIntakeId !== 'string'
      || typeof item.contentFingerprint !== 'string'
      || !/^[0-9a-f]{64}$/.test(item.contentFingerprint)
      || typeof item.receivedAt !== 'number'
      || !Number.isFinite(item.receivedAt)
      || typeof item.rawUrl !== 'string'
      || typeof item.rawTitle !== 'string'
      || typeof item.rawText !== 'string'
      || typeof item.sourceApp !== 'string'
      || typeof item.platform !== 'string'
      || typeof item.receivedVia !== 'string'
      || (item.retryState !== undefined && !allowedRetryStates.has(item.retryState))
    ) throw new Error('Pending share storage contains an invalid record.');
  }
  return parsed as PendingSharePayload[];
}

function parseStoredSession(raw: string): Session {
  const parsed: unknown = JSON.parse(raw);
  if (!parsed || typeof parsed !== 'object') throw new Error('Stored session is invalid.');
  const record = parsed as Record<string, unknown>;
  const user = record.user;
  if (
    typeof record.token !== 'string'
    || !record.token
    || !user
    || typeof user !== 'object'
  ) throw new Error('Stored session is invalid.');
  const userRecord = user as Record<string, unknown>;
  if (
    typeof userRecord.userId !== 'string'
    || typeof userRecord.email !== 'string'
    || typeof userRecord.displayName !== 'string'
  ) throw new Error('Stored session is invalid.');
  return record as unknown as Session;
}
export function restorePendingShareQueue(
  storedItems: PendingSharePayload[],
  currentItems: PendingSharePayload[],
  storedReceipts: Record<string, string>,
  currentReceipts: Record<string, string>,
  now = Date.now(),
): { items: PendingSharePayload[]; receipts: Record<string, string> } {
  const items = updatePendingShareQueue([...storedItems, ...currentItems], (current) => current, now);
  const receipts = filterPendingShareReceipts({ ...storedReceipts, ...currentReceipts }, items);
  return { items, receipts };
}

export function updatePendingShareQueue(current: PendingSharePayload[], update: (items: PendingSharePayload[]) => PendingSharePayload[], now = Date.now()): PendingSharePayload[] {
  return filterFreshPendingShares(dedupePendingShares(update(filterFreshPendingShares(current, now))), now);
}
export function applyPendingShareDelivery(
  items: PendingSharePayload[],
  receipts: Record<string, string>,
  deliveryId: string,
  item: PendingSharePayload,
): { items: PendingSharePayload[]; receipts: Record<string, string>; added: boolean } {
  if (receipts[deliveryId]) return { items, receipts, added: false };
  return {
    items: updatePendingShareQueue(items, (current) => [...current, item]),
    receipts: { ...receipts, [deliveryId]: item.clientIntakeId },
    added: true,
  };
}
export function scopePendingShare(item: PendingSharePayload, userId?: string | null, intendedFolderId?: string | null): PendingSharePayload {
  return {
    ...item,
    ...(userId ? { userId } : {}),
    ...(userId && intendedFolderId ? { intendedFolderId } : {}),
  };
}

export function shouldInvalidateSession(
  request: { epoch: number; token: string; userId: string },
  current: Session | null,
  currentEpoch: number,
): boolean {
  return Boolean(current && request.epoch === currentEpoch && request.token === current.token && request.userId === current.user.userId);
}

export function isSessionOperationCurrent(
  request: { epoch: number; token: string; userId: string },
  current: Session | null,
  currentEpoch: number,
): boolean {
  return shouldInvalidateSession(request, current, currentEpoch);
}

export function confirmPendingShareOwnership(
  items: PendingSharePayload[],
  userId: string,
  intendedFolderId: string,
  clientIntakeId?: string,
): PendingSharePayload[] {
  return items.map((item) => {
    if (clientIntakeId && item.clientIntakeId !== clientIntakeId) return item;
    if (item.intendedFolderId || (item.userId && item.userId !== userId)) return item;
    return { ...item, userId, intendedFolderId };
  });
}
export function isPendingShareReplayable(item: PendingSharePayload, userId: string, folderId: string, now = Date.now()): boolean {
  return !isPendingShareExpired(item, now)
    && item.userId === userId
    && item.intendedFolderId === folderId
    && item.retryState !== 'validation'
    && item.retryState !== 'conflict'
    && item.retryState !== 'auth';
}

export function pendingShareReplaySignature(items: PendingSharePayload[], userId: string, folderId: string, now = Date.now()): string | null {
  const replayableIds = items
    .filter((item) => isPendingShareReplayable(item, userId, folderId, now))
    .map((item) => item.clientIntakeId)
    .sort();
  return replayableIds.length ? replayableIds.join('|') : null;
}

export function pendingShareReplayTrigger(input: {
  durableGeneration: string | null;
  sessionUserId?: string | null;
  placesFolderId?: string | null;
  pendingShares: PendingSharePayload[];
}): string | null {
  if (!input.durableGeneration || !input.sessionUserId || !input.placesFolderId) return null;
  const replayableSignature = pendingShareReplaySignature(input.pendingShares, input.sessionUserId, input.placesFolderId);
  return replayableSignature ? `${input.sessionUserId}:${input.placesFolderId}:${replayableSignature}` : null;
}


export default function App() {
  const { hasShareIntent, shareIntent, resetShareIntent, error: shareIntentError } = useShareIntent({
    scheme: 'picturejournal',
    disabled: Platform.OS === 'web',
    resetOnBackground: false,
  });
  const [screen, setScreen] = useState<Screen>('auth');
  const [mode, setMode] = useState<AuthMode>('login');
  const [session, setSession] = useState<Session | null>(null);
  const [email, setEmail] = useState(developmentBuild ? 'demo@picturejournal.local' : '');
  const [displayName, setDisplayName] = useState(developmentBuild ? 'Picture Keeper' : '');
  const [password, setPassword] = useState(developmentBuild ? 'password123' : '');
  const [folders, setFolders] = useState<Folder[]>([]);
  const [selectedDiaryFolderId, setSelectedDiaryFolderId] = useState<string | null>(null);
  const [selectedPlacesFolderId, setSelectedPlacesFolderId] = useState<string | null>(null);
  const diaryFolder = folders.find((item) => item.folderId === selectedDiaryFolderId && item.type === 'PHOTO_DIARY') ?? null;
  const placesFolder = folders.find((item) => item.folderId === selectedPlacesFolderId && item.type === 'REELS_PLACE') ?? null;
  const [entries, setEntries] = useState<DiaryEntry[]>([]);
  const [selectedEntry, setSelectedEntry] = useState<DiaryEntry | null>(null);
  const [places, setPlaces] = useState<SavedPlace[]>([]);
  const [selectedPlace, setSelectedPlace] = useState<SavedPlace | null>(null);
  const [unresolved, setUnresolved] = useState<ShareIntake[]>([]);
  const [selectedIntake, setSelectedIntake] = useState<ShareIntake | null>(null);
  const [pendingShares, setPendingShares] = useState<PendingSharePayload[]>([]);
  const [pendingShareGeneration, setPendingShareGeneration] = useState<string | null>(null);
  const pendingSharesRef = useRef<PendingSharePayload[]>([]);
  const pendingShareReceiptsRef = useRef<Record<string, string>>({});
  const pendingShareMutationRef = useRef<Promise<void>>(Promise.resolve());
  const capturedIntentKeysRef = useRef(new Set<string>());
  const resetShareIntentRef = useRef(resetShareIntent);
  const mutationInFlightRef = useRef(false);
  const sessionRef = useRef<Session | null>(null);
  const sessionEpochRef = useRef(0);
  const replayAbortRef = useRef<AbortController | null>(null);
  const lastAutoReplayTriggerRef = useRef<string | null>(null);
  const selectedPlacesFolderIdRef = useRef<string | null>(null);
  const [status, setStatus] = useState('Secure session restore pending.');
  const [restoreRetryAvailable, setRestoreRetryAvailable] = useState(false);
  const [restoringSession, setRestoringSession] = useState(true);
  const [busy, setBusy] = useState(false);
  const [draftTitle, setDraftTitle] = useState('A clear memory from today');
  const [draftBody, setDraftBody] = useState('What happened, who was there, and why this photo matters.');
  const [draftTags, setDraftTags] = useState('family, weekend');
  const [draftPlace, setDraftPlace] = useState('Pinned location');
  const [draftLat, setDraftLat] = useState('');
  const [draftLng, setDraftLng] = useState('');
  const [coordinateProvenance, setCoordinateProvenance] = useState<CoordinateProvenance | null>(null);
  const [photo, setPhoto] = useState<PickedPhoto | null>(null);
  const [repairName, setRepairName] = useState('');
  const [repairAddress, setRepairAddress] = useState('');
  const [repairRegion, setRepairRegion] = useState('');
  const [repairLat, setRepairLat] = useState('');
  const [repairLng, setRepairLng] = useState('');
  const [repairCategory, setRepairCategory] = useState('saved');

  const resetAccountState = useCallback(() => {
    sessionRef.current = null;
    setSession(null);
    setMode('login');
    setEmail('');
    setDisplayName('');
    setPassword('');
    setFolders([]);
    setSelectedDiaryFolderId(null);
    setSelectedPlacesFolderId(null);
    setEntries([]);
    setSelectedEntry(null);
    setPlaces([]);
    setSelectedPlace(null);
    setUnresolved([]);
    setSelectedIntake(null);
    setDraftTitle('');
    setDraftBody('');
    setDraftTags('');
    setDraftPlace('');
    setDraftLat('');
    setDraftLng('');
    setCoordinateProvenance(null);
    setPhoto(null);
    setRepairName('');
    setRepairAddress('');
    setRepairRegion('');
    setRepairLat('');
    setRepairLng('');
    setRepairCategory('saved');
    setRestoreRetryAvailable(false);
    setRestoringSession(false);
    setScreen('auth');
  }, []);

  useEffect(() => {
    resetShareIntentRef.current = resetShareIntent;
  }, [resetShareIntent]);

  useEffect(() => {
    sessionRef.current = session;
  }, [session]);

  useEffect(() => {
    selectedPlacesFolderIdRef.current = selectedPlacesFolderId;
  }, [selectedPlacesFolderId]);

  const api = useCallback(
    async <T,>(path: string, options: RequestInit = {}): Promise<T> => {
      const requestSession = sessionRef.current;
      const requestEpoch = sessionEpochRef.current;
      const headers = new Headers(options.headers);
      if (!(options.body instanceof FormData)) headers.set('Content-Type', 'application/json');
      if (requestSession) headers.set('Authorization', `Bearer ${requestSession.token}`);
      const response = await fetch(`${apiBaseUrl}${path}`, { ...options, headers });
      if (!response.ok) {
        const message = await response.text();
        if (
          requestSession
          && (response.status === 401 || response.status === 403)
          && shouldInvalidateSession(
            { epoch: requestEpoch, token: requestSession.token, userId: requestSession.user.userId },
            sessionRef.current,
            sessionEpochRef.current,
          )
        ) {
          sessionEpochRef.current += 1;
          replayAbortRef.current?.abort();
          resetAccountState();
          await Promise.allSettled([
            secureDelete(sessionKey),
            secureDelete(diaryFolderKey),
            secureDelete(placesFolderKey),
          ]);
          setStatus('Session expired. Sign in again; queued shares are retained.');
        }
        throw new ApiError(response.status, `${response.status} ${message || response.statusText}`);
      }
      if (
        requestSession
        && !isSessionOperationCurrent(
          { epoch: requestEpoch, token: requestSession.token, userId: requestSession.user.userId },
          sessionRef.current,
          sessionEpochRef.current,
        )
      ) {
        const stale = new Error('Stale session response ignored.');
        stale.name = 'AbortError';
        throw stale;
      }
      if (response.status === 204) return undefined as T;
      const result = (await response.json()) as T;
      if (
        requestSession
        && !isSessionOperationCurrent(
          { epoch: requestEpoch, token: requestSession.token, userId: requestSession.user.userId },
          sessionRef.current,
          sessionEpochRef.current,
        )
      ) {
        const stale = new Error('Stale session response ignored.');
        stale.name = 'AbortError';
        throw stale;
      }
      return result;
    },
    [resetAccountState],
  );

  const updatePendingShares = useCallback(async (
    update: (current: PendingSharePayload[]) => PendingSharePayload[],
    updateReceipts: (current: Record<string, string>) => Record<string, string> = (current) => current,
  ) => {
    const commit = async () => {
      const items = updatePendingShareQueue(pendingSharesRef.current, update);
      const receipts = filterPendingShareReceipts(updateReceipts(pendingShareReceiptsRef.current), items);
      if (
        JSON.stringify(items) === JSON.stringify(pendingSharesRef.current)
        && JSON.stringify(receipts) === JSON.stringify(pendingShareReceiptsRef.current)
      ) return;
      const metadata = await secureSetChunked(pendingShareKey, JSON.stringify(items), receipts);
      pendingSharesRef.current = items;
      pendingShareReceiptsRef.current = receipts;
      setPendingShareGeneration(metadata.generation);
      setPendingShares(items);
    };
    const next = pendingShareMutationRef.current.then(commit, commit);
    pendingShareMutationRef.current = next.catch(() => undefined);
    await next;
  }, []);

  const enqueuePendingShare = useCallback(async (item: PendingSharePayload, deliveryId?: string): Promise<boolean> => {
    let added = false;
    let deliveryResult: ReturnType<typeof applyPendingShareDelivery> | undefined;
    await updatePendingShares(
      (current) => {
        if (!deliveryId) {
          added = true;
          return [...current, item];
        }
        const result = applyPendingShareDelivery(current, pendingShareReceiptsRef.current, deliveryId, item);
        deliveryResult = result;
        added = result.added;
        return result.items;
      },
      (receipts) => deliveryId
        ? (deliveryResult ?? applyPendingShareDelivery(pendingSharesRef.current, receipts, deliveryId, item)).receipts
        : receipts,
    );
    return added;
  }, [updatePendingShares]);

  const clearNativeReceipt = useCallback(async (deliveryId: string) => {
    await updatePendingShares((current) => current, (receipts) => {
      const { [deliveryId]: _, ...remaining } = receipts;
      return remaining;
    });
  }, [updatePendingShares]);

  const refreshFolders = useCallback(async () => {
    const data = await api<Folder[]>('/api/v1/folders');
    setFolders(data);
    return data;
  }, [api]);

  const refreshDiary = useCallback(async () => {
    if (!diaryFolder) return;
    const data = await api<DiaryEntry[]>(`/api/v1/folders/${diaryFolder.folderId}/diary-entries`);
    setEntries(data);
    setSelectedEntry((current) => current ?? data[0] ?? null);
  }, [api, diaryFolder]);

  const refreshPlaces = useCallback(async () => {
    if (!placesFolder) return;
    const data = await api<SavedPlace[]>(`/api/v1/folders/${placesFolder.folderId}/saved-places`);
    setPlaces(data);
    setSelectedPlace((current) => current ?? data[0] ?? null);
  }, [api, placesFolder]);

  const publishUnresolved = useCallback((serverItems: ShareIntake[]) => {
    setUnresolved(serverItems);
    setSelectedIntake((current) => {
      const retained = current ? serverItems.find((item) => item.intakeId === current.intakeId) : null;
      if (retained) return retained;
      setRepairName('');
      setRepairAddress('');
      setRepairRegion('');
      setRepairLat('');
      setRepairLng('');
      return serverItems[0] ?? null;
    });
  }, []);

  const refreshUnresolved = useCallback(async () => {
    if (!session || !placesFolder) return;
    const serverItems = await api<ShareIntake[]>(`/api/v1/folders/${placesFolder.folderId}/share-intake/unresolved`);
    publishUnresolved(serverItems);
  }, [api, placesFolder, publishUnresolved, session]);

  const replayPendingShares = useCallback(async (trigger: string) => {
    if (!session || !placesFolder) return;
    const epoch = sessionEpochRef.current;
    replayAbortRef.current?.abort();
    const controller = new AbortController();
    replayAbortRef.current = controller;
    const userId = session.user.userId;
    const folderId = placesFolder.folderId;
    const isSessionCurrent = () =>
      !controller.signal.aborted
      && sessionEpochRef.current === epoch
      && sessionRef.current?.user.userId === userId
      && selectedPlacesFolderIdRef.current === folderId;
    const isCurrent = () =>
      isSessionCurrent()
      && pendingShareReplayTrigger({
        durableGeneration: pendingShareGeneration,
        sessionUserId: userId,
        placesFolderId: folderId,
        pendingShares: pendingSharesRef.current,
      }) === trigger;
    const snapshot = pendingSharesRef.current;
    const remaining: PendingSharePayload[] = [];
    const processedIds = new Set<string>();
    for (const pending of snapshot) {
      if (!isPendingShareReplayable(pending, userId, folderId)) continue;
      processedIds.add(pending.clientIntakeId);
      try {
        const intake = await api<ShareIntake>('/api/v1/share-intake', {
          method: 'POST',
          signal: controller.signal,
          body: JSON.stringify({
            folderId,
            clientIntakeId: pending.clientIntakeId,
            contentFingerprint: pending.contentFingerprint,
            rawUrl: pending.rawUrl,
            rawTitle: pending.rawTitle,
            rawText: pending.rawText,
            sourceApp: pending.sourceApp,
            platform: pending.platform,
            receivedVia: pending.receivedVia,
          }),
        });
        if (!isCurrent()) return;
        if (intake.status === 'RESOLVED' && intake.resolvedPlace) setSelectedPlace(intake.resolvedPlace);
      } catch (error) {
        if (controller.signal.aborted || !isCurrent()) return;
        const message = error instanceof Error ? error.message : 'Share intake failed.';
        const statusCode = error instanceof ApiError ? error.status : undefined;
        remaining.push({ ...pending, lastError: message, retryState: classifyShareRetry(statusCode) });
      }
    }
    if (!isCurrent()) return;
    await updatePendingShares((current) => [
      ...current.filter((item) => !processedIds.has(item.clientIntakeId)),
      ...remaining,
    ]);
    if (!isSessionCurrent()) return;
    const [serverItems, serverPlaces] = await Promise.all([
      api<ShareIntake[]>(`/api/v1/folders/${folderId}/share-intake/unresolved`, { signal: controller.signal }),
      api<SavedPlace[]>(`/api/v1/folders/${folderId}/saved-places`, { signal: controller.signal }),
    ]);
    if (!isSessionCurrent()) return;
    setPlaces(serverPlaces);
    setSelectedPlace((current) => current ?? serverPlaces[0] ?? null);
    publishUnresolved(serverItems);
    const localPending = remaining.filter((item) => item.intendedFolderId === folderId).length;
    if (localPending) {
      const blocked = remaining.filter((item) => item.retryState === 'validation' || item.retryState === 'conflict');
      setStatus(blocked.length
        ? `${blocked.length} share payload${blocked.length === 1 ? '' : 's'} need manual remediation: ${blocked.map((item) => item.lastError).join(' ')}`
        : `${localPending} local share payload${localPending === 1 ? '' : 's'} retained for an explicit retry.`);
    }
  }, [api, pendingShareGeneration, placesFolder, publishUnresolved, session, updatePendingShares]);

  const refreshUnresolvedFromPendingShares = useCallback(async () => {
    const trigger = pendingShareReplayTrigger({
      durableGeneration: pendingShareGeneration,
      sessionUserId: session?.user.userId,
      placesFolderId: placesFolder?.folderId,
      pendingShares: pendingSharesRef.current,
    });
    if (trigger) {
      await replayPendingShares(trigger);
      return;
    }
    await Promise.all([refreshPlaces(), refreshUnresolved()]);
  }, [pendingShareGeneration, placesFolder, refreshPlaces, refreshUnresolved, replayPendingShares, session]);

  const restoreSession = useCallback(async () => {
    const restoreEpoch = sessionEpochRef.current;
    const isCurrent = () => sessionEpochRef.current === restoreEpoch && sessionRef.current === null;
    const queueRestoreState: { error?: string } = {};
    const [storedSession, storedShares, storedDiaryFolderId, storedPlacesFolderId] = await Promise.all([
      secureGet(sessionKey),
      secureGetChunked(pendingShareKey).catch((error: Error) => {
        queueRestoreState.error = error.message;
        return null;
      }),
      secureGet(diaryFolderKey),
      secureGet(placesFolderKey),
    ]);
    if (!isCurrent()) return;
    if (storedShares) {
      const parsedShares = parsePendingShares(storedShares.value);
      await updatePendingShares(
        (current) => restorePendingShareQueue(parsedShares, current, storedShares.metadata.receipts, pendingShareReceiptsRef.current).items,
        (receipts) => restorePendingShareQueue(parsedShares, pendingSharesRef.current, storedShares.metadata.receipts, receipts).receipts,
      );
      if (!isCurrent()) return;
    }
    setSelectedDiaryFolderId(storedDiaryFolderId);
    setSelectedPlacesFolderId(storedPlacesFolderId);
    if (!storedSession) {
      setRestoreRetryAvailable(false);
      setStatus(queueRestoreState.error
        ? `Sign in to continue. Pending share recovery needs manual attention: ${queueRestoreState.error}`
        : 'Sign in to continue the securely retained share recovery flow.');
      return;
    }
    let parsed: Session;
    try {
      parsed = parseStoredSession(storedSession);
    } catch {
      if (!isCurrent()) return;
      await secureDelete(sessionKey);
      if (!isCurrent()) return;
      setRestoreRetryAvailable(false);
      setStatus('Stored session was invalid. Sign in again; pending share storage was left untouched.');
      return;
    }
    let response: Response;
    try {
      response = await fetch(`${apiBaseUrl}/api/v1/auth/me`, { headers: { Authorization: `Bearer ${parsed.token}` } });
    } catch {
      if (!isCurrent()) return;
      setRestoreRetryAvailable(true);
      setStatus('Session validation is temporarily unavailable. Retry preserves the stored session and queued shares.');
      return;
    }
    if (!isCurrent()) return;
    const classification = classifyAuthResponse(response.ok ? 200 : response.status);
    if (classification === 'reauthenticate') {
      await secureDelete(sessionKey);
      if (!isCurrent()) return;
      setRestoreRetryAvailable(false);
      setStatus('Stored session was rejected. Sign in again; queued shares are retained.');
      return;
    }
    if (classification === 'retry') {
      setRestoreRetryAvailable(true);
      setStatus(`Session validation is temporarily unavailable (${response.status}). Retry preserves the stored session and queued shares.`);
      return;
    }
    const user = (await response.json()) as UserAccount;
    if (!isCurrent()) return;
    const validated = { token: parsed.token, user };
    await secureSet(sessionKey, JSON.stringify(validated));
    if (!isCurrent()) return;
    sessionEpochRef.current += 1;
    sessionRef.current = validated;
    setSession(validated);
    setRestoreRetryAvailable(false);
    setStatus(queueRestoreState.error
      ? `Secure session restored. Pending share recovery needs manual attention: ${queueRestoreState.error}`
      : 'Secure session restored and validated.');
    setScreen('folders');
  }, [updatePendingShares]);

  useEffect(() => {
    restoreSession()
      .catch((error: Error) => {
        setRestoreRetryAvailable(true);
        setStatus(error.message);
      })
      .finally(() => setRestoringSession(false));
  }, [restoreSession]);

  useEffect(() => {
    const captureUrl = async (url: string | null) => {
      if (!url) return;
      const parsed = Linking.parse(url);
      const rawUrl = text(parsed.queryParams?.url);
      const rawTitle = text(parsed.queryParams?.title);
      const rawText = text(parsed.queryParams?.text);
      if (text(parsed.queryParams?.dataUrl)) {
        await getShareIntent(url);
        return;
      }
      if (!rawUrl && !rawTitle && !rawText) return;
      const intentKey = `link:${await Crypto.digestStringAsync(Crypto.CryptoDigestAlgorithm.SHA256, url)}`;
      if (capturedIntentKeysRef.current.has(intentKey)) return;
      capturedIntentKeysRef.current.add(intentKey);
      try {
        const item = scopePendingShare(
          await createPendingShare({ rawUrl, rawTitle, rawText, sourceApp: 'deep-link', platform: Platform.OS, receivedVia: 'web_deeplink' }),
          session?.user.userId,
          placesFolder?.folderId,
        );
        await enqueuePendingShare(item);
        setStatus('Share captured locally. It will be saved after authentication and explicit folder binding.');
        setScreen(session ? 'placesList' : 'auth');
      } finally {
        capturedIntentKeysRef.current.delete(intentKey);
      }
    };
    Linking.getInitialURL().then(captureUrl).catch((error: Error) => setStatus(error.message));
    const subscription = Linking.addEventListener('url', (event) => captureUrl(event.url).catch((error: Error) => setStatus(error.message)));
    return () => subscription.remove();
  }, [enqueuePendingShare, placesFolder, session]);

  useEffect(() => {
    if (shareIntentError) setStatus(`Native share error: ${shareIntentError}`);
    if (!hasShareIntent) return;
    const captureNativeShare = async () => {
      const rawUrl = shareIntent.webUrl ?? '';
      const rawTitle = shareIntent.meta?.title ?? '';
      const rawText = shareIntent.text ?? '';
      if (!rawUrl && !rawTitle && !rawText) return;
      const deliveryId = `native:${await Crypto.digestStringAsync(
        Crypto.CryptoDigestAlgorithm.SHA256,
        lengthPrefixed([rawUrl, rawTitle, rawText]),
      )}`;
      if (capturedIntentKeysRef.current.has(deliveryId)) return;
      capturedIntentKeysRef.current.add(deliveryId);
      try {
        const item = scopePendingShare(
          await createPendingShare({ rawUrl, rawTitle, rawText, sourceApp: 'native-share-sheet', platform: Platform.OS, receivedVia: 'native_share' }),
          session?.user.userId,
          placesFolder?.folderId,
        );
        await enqueuePendingShare(item, deliveryId);
        resetShareIntentRef.current(true);
        await clearNativeReceipt(deliveryId);
        if (__DEV__) console.info('PICTUREJOURNAL_SHARE_RECEIVED', item.clientIntakeId);
        capturedIntentKeysRef.current.delete(deliveryId);
        setStatus('Native share captured locally for automatic save after authentication and folder binding.');
        setScreen(session ? 'placesList' : 'auth');
      } catch (error) {
        capturedIntentKeysRef.current.delete(deliveryId);
        throw error;
      }
    };
    captureNativeShare().catch((error: Error) => setStatus(error.message));
  }, [clearNativeReceipt, enqueuePendingShare, hasShareIntent, placesFolder, session, shareIntent, shareIntentError]);


  useEffect(() => {
    if (hasShareIntent || Object.keys(pendingShareReceiptsRef.current).length === 0) return;
    updatePendingShares((current) => current, () => ({})).catch((error: Error) => setStatus(error.message));
  }, [hasShareIntent, pendingShares, updatePendingShares]);
  useEffect(() => {
    if (!session) return;
    refreshFolders().catch((error: Error) => setStatus(error.message));
  }, [session, refreshFolders]);

  useEffect(() => {
    if (!diaryFolder && !placesFolder) return;
    const replayTrigger = pendingShareReplayTrigger({
      durableGeneration: pendingShareGeneration,
      sessionUserId: session?.user.userId,
      placesFolderId: placesFolder?.folderId,
      pendingShares,
    });
    Promise.all([
      refreshDiary(),
      replayTrigger ? Promise.resolve() : refreshPlaces(),
      replayTrigger ? Promise.resolve() : refreshUnresolved(),
    ]).catch((error: Error) => {
      if (error.name !== 'AbortError') setStatus(error.message);
    });
  }, [diaryFolder, pendingShareGeneration, pendingShares, placesFolder, refreshDiary, refreshPlaces, refreshUnresolved, session]);
  useEffect(() => {
    const trigger = pendingShareReplayTrigger({
      durableGeneration: pendingShareGeneration,
      sessionUserId: session?.user.userId,
      placesFolderId: placesFolder?.folderId,
      pendingShares,
    });
    if (!trigger) {
      lastAutoReplayTriggerRef.current = null;
      return;
    }
    if (lastAutoReplayTriggerRef.current === trigger) return;
    lastAutoReplayTriggerRef.current = trigger;
    replayPendingShares(trigger).catch((error: Error) => {
      if (error.name !== 'AbortError') setStatus(error.message);
    });
  }, [pendingShareGeneration, pendingShares, placesFolder, replayPendingShares, session]);
  useEffect(() => {
    if (!pendingShares.length) return;
    const nextExpiry = Math.min(...pendingShares.map((item) => item.receivedAt + pendingShareTtlMs));
    const timeout = setTimeout(() => {
      updatePendingShares((current) => current).catch((error: Error) => setStatus(error.message));
    }, Math.max(0, nextExpiry - Date.now() + 1));
    return () => clearTimeout(timeout);
  }, [pendingShares, updatePendingShares]);

  const run = async (label: string, action: () => Promise<void>) => {
    if (mutationInFlightRef.current) return;
    mutationInFlightRef.current = true;
    setBusy(true);
    setStatus(label);
    try {
      await action();
    } catch (error) {
      setStatus(error instanceof Error ? error.message : 'Unexpected error');
    } finally {
      mutationInFlightRef.current = false;
      setBusy(false);
    }
  };

  const discardLocalPendingShare = (clientIntakeId: string) =>
    run('Discarding blocked local share payload.', async () => {
      await updatePendingShares((current) => current.filter((item) => item.clientIntakeId !== clientIntakeId));
      setStatus('Blocked local share payload discarded.');
    });

  const authenticate = () =>
    run(mode === 'signup' ? 'Creating account.' : 'Signing in.', async () => {
      if (mode === 'signup') await api<UserAccount>('/api/v1/auth/signup', { method: 'POST', body: JSON.stringify({ email, displayName, password }) });
      const next = await api<Session>('/api/v1/auth/login', { method: 'POST', body: JSON.stringify({ email, password }) });
      try {
        await secureSet(sessionKey, JSON.stringify(next));
      } catch (error) {
        await fetch(`${apiBaseUrl}/api/v1/auth/logout`, { method: 'POST', headers: { Authorization: `Bearer ${next.token}` } }).catch(() => undefined);
        throw error;
      }
      await updatePendingShares((current) => current.map((item) => item.retryState === 'auth' ? { ...item, retryState: 'retryable' } : item));
      sessionEpochRef.current += 1;
      sessionRef.current = next;
      setSession(next);
      setScreen('folders');
      setStatus('Authenticated session securely persisted.');
    });

  const logout = () =>
    run('Logging out and purging local pending shares.', async () => {
      const endingSession = sessionRef.current;
      sessionEpochRef.current += 1;
      replayAbortRef.current?.abort();
      resetAccountState();
      setPendingShares([]);
      pendingSharesRef.current = [];
      pendingShareReceiptsRef.current = {};
      setPendingShareGeneration(null);
      lastAutoReplayTriggerRef.current = null;
      await pendingShareMutationRef.current.catch(() => undefined);
      const cleanupResults = await Promise.allSettled([
        secureDelete(sessionKey),
        secureDeleteChunked(pendingShareKey),
        secureDelete(diaryFolderKey),
        secureDelete(placesFolderKey),
      ]);
      const cleanupFailures = cleanupResults.filter((result): result is PromiseRejectedResult => result.status === 'rejected');
      setPendingShares([]);
      pendingSharesRef.current = [];
      pendingShareReceiptsRef.current = {};
      setPendingShareGeneration(null);
      lastAutoReplayTriggerRef.current = null;
      setUnresolved([]);
      let warning: string | null = cleanupFailures.length
        ? `Local cleanup warning: ${cleanupFailures.map((result) => result.reason instanceof Error ? result.reason.message : 'storage cleanup failed').join(' ')}`
        : null;
      if (endingSession) {
        try {
          const response = await fetch(`${apiBaseUrl}/api/v1/auth/logout`, {
            method: 'POST',
            headers: { Authorization: `Bearer ${endingSession.token}` },
          });
          if (!response.ok) throw new Error(`${response.status} ${await response.text() || response.statusText}`);
        } catch (error) {
          warning = `${warning ? `${warning} ` : ''}${error instanceof Error ? error.message : 'Backend logout failed'}`;
        }
      }
      setStatus(warning ? `Local data purged; backend logout warning: ${warning}` : 'Logged out. Session and local pending share payloads purged.');
    });

  const bindPendingShareToFolder = async (clientIntakeId: string, folderId: string) => {
    const currentSession = sessionRef.current;
    if (!currentSession) throw new Error('Sign in before binding shared content to a folder.');
    const target = pendingSharesRef.current.find((item) => item.clientIntakeId === clientIntakeId);
    if (!target) throw new Error('Pending share is no longer available.');
    if (target.userId && target.userId !== currentSession.user.userId) {
      throw new Error('Pending share belongs to another account.');
    }
    if (target.intendedFolderId && target.intendedFolderId !== folderId) {
      throw new Error('Pending share is already bound to another folder.');
    }
    await updatePendingShares((current) => confirmPendingShareOwnership(
      current,
      currentSession.user.userId,
      folderId,
      clientIntakeId,
    ));
  };

  const createFolder = (type: 'PHOTO_DIARY' | 'REELS_PLACE') =>
    run(`Creating ${type} folder.`, async () => {
      const created = await api<Folder>('/api/v1/folders', {
        method: 'POST',
        body: JSON.stringify({
          type,
          name: type === 'PHOTO_DIARY' ? 'My Photo Diary' : 'My Saved Places',
          description: type === 'PHOTO_DIARY' ? 'Photo Diary workspace' : 'Saved Places workspace',
        }),
      });
      if (type === 'PHOTO_DIARY') {
        await secureSet(diaryFolderKey, created.folderId);
        setSelectedDiaryFolderId(created.folderId);
        setSelectedEntry(null);
        setEntries([]);
      } else {
        await secureSet(placesFolderKey, created.folderId);
        setSelectedPlacesFolderId(created.folderId);
        setSelectedPlace(null);
        setSelectedIntake(null);
        setPlaces([]);
        setUnresolved([]);
      }
      await refreshFolders();
      setStatus(`${type} folder ready.`);
    });

  const pickOnePhoto = () =>
    run('Opening one-photo picker.', async () => {
      const permission = await ImagePicker.requestMediaLibraryPermissionsAsync();
      if (!permission.granted) throw new Error('Photo library permission is required to create a Photo Diary entry.');
      const result = await ImagePicker.launchImageLibraryAsync({ mediaTypes: ImagePicker.MediaTypeOptions.Images, allowsMultipleSelection: false, exif: true, quality: 0.9 });
      if (result.canceled || result.assets.length !== 1) throw new Error('Choose exactly one real image.');
      const asset = result.assets[0];
      const exif = (asset.exif ?? {}) as JsonRecord;
      const latitude = normalizeExifCoordinate(exif.GPSLatitude, exif.GPSLatitudeRef, 'S');
      const longitude = normalizeExifCoordinate(exif.GPSLongitude, exif.GPSLongitudeRef, 'W');
      setPhoto({
        uri: asset.uri,
        name: asset.fileName ?? `photo-${Date.now()}.jpg`,
        mimeType: asset.mimeType ?? 'image/jpeg',
        latitude,
        longitude,
        takenAt: normalizeExifTakenAt(exif.DateTimeOriginal),
      });
      if (latitude !== undefined && longitude !== undefined) {
        setDraftLat(String(latitude));
        setDraftLng(String(longitude));
        setCoordinateProvenance('exif');
        setStatus('Trusted EXIF coordinates found. Review before saving.');
      } else {
        setDraftLat('');
        setDraftLng('');
        setCoordinateProvenance(null);
        setStatus('No trusted EXIF coordinates found. Use current location or enter final coordinates before saving.');
      }
    });

  const useCurrentLocation = () =>
    run('Requesting current location for manual coordinate fallback.', async () => {
      const permission = await Location.requestForegroundPermissionsAsync();
      if (permission.status !== 'granted') throw new Error('Location permission denied. Enter latitude and longitude manually.');
      const current = await Location.getCurrentPositionAsync({ accuracy: Location.Accuracy.Balanced });
      if (!Number.isFinite(current.coords.latitude) || !Number.isFinite(current.coords.longitude)) throw new Error('Current location is invalid.');
      setDraftLat(String(current.coords.latitude));
      setDraftLng(String(current.coords.longitude));
      setCoordinateProvenance('current');
      setStatus('Current coordinates applied.');
    });

  const createDiaryEntry = () =>
    run('Uploading one photo and saving diary entry.', async () => {
      if (!diaryFolder) throw new Error('Create or select a Photo Diary folder first.');
      if (!photo) throw new Error('Photo Diary requires exactly one real image.');
      const latitude = parseCoordinate(draftLat, 'latitude');
      const longitude = parseCoordinate(draftLng, 'longitude');
      if (!coordinateProvenance) throw new Error('Choose trusted EXIF, current location, or enter coordinates manually before saving.');
      const form = new FormData();
      if (Platform.OS === 'web') {
        const response = await fetch(photo.uri);
        if (!response.ok) throw new Error(`Could not read the selected browser image (${response.status}).`);
        form.append('file', await response.blob(), photo.name);
      } else {
        form.append('file', { uri: photo.uri, name: photo.name, type: photo.mimeType } as unknown as Blob);
      }
      form.append('intendedFolderId', diaryFolder.folderId);
      const media = await api<MediaAsset>('/api/v1/media/direct-upload', { method: 'POST', body: form });
      let created: DiaryEntry;
      try {
        created = await api<DiaryEntry>(`/api/v1/folders/${diaryFolder.folderId}/diary-entries`, {
          method: 'POST',
          body: JSON.stringify({
            mediaId: media.mediaId,
            title: draftTitle,
            body: draftBody,
            placeName: draftPlace,
            latitude,
            longitude,
            capturedAt: media.takenAt ?? photo.takenAt ?? new Date().toISOString(),
            tags: draftTags.split(',').map((tag) => tag.trim()).filter(Boolean),
          }),
        });
      } catch (error) {
        const detail = error instanceof Error ? error.message : 'Unknown diary save failure.';
        throw new Error(`Media ${media.mediaId} was uploaded but the diary entry was not created. Retry with a new upload or remove the orphan on the server. ${detail}`);
      }
      await refreshDiary();
      setSelectedEntry(created);
      setScreen('diaryDetail');
      setStatus('Photo Diary entry saved.');
    });

  const repairUnresolved = () =>
    run('Resolving unresolved share intake with manual repair.', async () => {
      if (!placesFolder || !selectedIntake) throw new Error('Choose a server unresolved intake first.');
      const latitude = parseCoordinate(repairLat, 'latitude');
      const longitude = parseCoordinate(repairLng, 'longitude');
      const resolved = await api<{ intake: ShareIntake; savedPlace: SavedPlace }>(`/api/v1/share-intake/${selectedIntake.intakeId}/resolve`, {
        method: 'POST',
        body: JSON.stringify({
          folderId: placesFolder.folderId,
          candidateId: selectedIntake.candidates[0]?.candidateId ?? null,
          manualName: repairName || selectedIntake.rawTitle,
          category: repairCategory,
          address: repairAddress,
          regionText: repairRegion,
          latitude,
          longitude,
          summary: 'Manually repaired from unresolved inbox.',
          whyRecommended: selectedIntake.rawText,
          keywords: ['repaired'],
          visitStatus: 'WANT_TO_GO',
        }),
      });
      setSelectedPlace(resolved.savedPlace);
      await Promise.all([refreshPlaces(), refreshUnresolved()]);
      setStatus('Unresolved share repaired into a saved place.');
    });

  const navDisabled = !session && screen !== 'auth';

  return (
    <View style={styles.app}>
      <StatusBar style="dark" />
      <ScrollView horizontal showsHorizontalScrollIndicator={false} style={styles.nav} contentContainerStyle={styles.navContent}>
        {screens.map((item) => (
          <Pressable key={item.key} disabled={item.key !== 'auth' && navDisabled} onPress={() => setScreen(item.key)} style={[styles.navPill, screen === item.key && styles.navPillActive, item.product === 'Photo Diary' && styles.diaryPill, item.product === 'Saved Places' && styles.placePill]}>
            <Text style={[styles.navText, screen === item.key && styles.navTextActive]}>{item.label}</Text>
          </Pressable>
        ))}
      </ScrollView>
      <ScrollView contentContainerStyle={styles.container}>
        <View style={styles.header}>
          <Text style={styles.eyebrow}>PictureJournal</Text>
          <Text style={styles.title}>{screens.find((item) => item.key === screen)?.label}</Text>
          {developmentBuild && <Text style={styles.description}>API: {apiBaseUrl} · {session ? `${session.user.displayName} signed in` : 'signed out'} · Diary: {diaryFolder?.name ?? 'none'} · Places: {placesFolder?.name ?? 'none'}</Text>}
          <Text style={styles.status}>{busy ? 'Working… ' : ''}{status}</Text>
        </View>

        {screen === 'auth' && (
          <View style={styles.card}>
            <Text style={styles.cardTitle}>Complete auth and secure session persistence</Text>
            <Segment values={['login', 'signup']} value={mode} onChange={(value) => setMode(value as AuthMode)} />
            <Field label="Email" value={email} onChangeText={setEmail} autoCapitalize="none" />
            {mode === 'signup' && <Field label="Display name" value={displayName} onChangeText={setDisplayName} />}
            <Field label="Password" value={password} onChangeText={setPassword} secureTextEntry />
            <Action label={mode === 'signup' ? 'Create account and sign in' : 'Sign in'} onPress={authenticate} disabled={busy || restoringSession} />
            {restoreRetryAvailable && (
              <Action
                label="Retry secure session validation"
                onPress={() => run('Retrying secure session validation.', async () => {
                  setRestoringSession(true);
                  try {
                    await restoreSession();
                  } finally {
                    setRestoringSession(false);
                  }
                })}
                secondary
                disabled={busy || restoringSession}
              />
            )}
          </View>
        )}

        {screen === 'folders' && (
          <View style={styles.card}>
            <Text style={styles.cardTitle}>Folder Select</Text>
            <Text style={styles.description}>All diary, media, share, and place writes stay folder-scoped through the backend policy boundary.</Text>
            <Action label="Refresh folders" onPress={() => run('Refreshing folders.', async () => { await refreshFolders(); })} />
            {!diaryFolder && <Action label="Create Photo Diary folder" onPress={() => createFolder('PHOTO_DIARY')} secondary disabled={busy} />}
            {!placesFolder && <Action label="Create Saved Places folder" onPress={() => createFolder('REELS_PLACE')} secondary disabled={busy} />}
            {folders.map((item) => (
              <Pressable key={item.folderId} disabled={busy} onPress={() => run(`Binding ${item.name}.`, async () => {
                if (item.type === 'PHOTO_DIARY') {
                  await secureSet(diaryFolderKey, item.folderId);
                  setSelectedDiaryFolderId(item.folderId);
                  setSelectedEntry(null);
                  setEntries([]);
                } else if (item.type === 'REELS_PLACE') {
                  await secureSet(placesFolderKey, item.folderId);
                  setSelectedPlacesFolderId(item.folderId);
                  setSelectedPlace(null);
                  setSelectedIntake(null);
                  setPlaces([]);
                  setUnresolved([]);
                }
                setStatus(`${item.name} is the active ${item.type} folder.`);
              })} style={[styles.row, (selectedDiaryFolderId === item.folderId || selectedPlacesFolderId === item.folderId) && styles.rowActive]}>
                <Text style={styles.rowTitle}>{item.name}</Text>
                <Text style={styles.description}>{item.type} · {item.role} · {item.description}</Text>
              </Pressable>
            ))}
            <Action label="Logout and purge pending shares" onPress={logout} danger disabled={busy} />
          </View>
        )}

        {screen === 'diaryFeed' && (
          <View style={[styles.card, styles.diaryCard]}>
            <Text style={styles.cardTitle}>Photo Diary Feed</Text>
            <Text style={styles.description}>Warm photo-first journal stream, separate from Saved Places.</Text>
            <Action label="Refresh diary feed" onPress={() => run('Refreshing diary.', refreshDiary)} />
            <Action label="Compose one-photo diary" onPress={() => setScreen('diaryComposer')} secondary />
            {entries.map((entry) => (
              <Pressable key={entry.entryId} onPress={() => { setSelectedEntry(entry); setScreen('diaryDetail'); }} style={styles.diaryItem}>
                {session && <Image source={{ uri: `${apiBaseUrl}/api/v1/media/${entry.mediaId}/binary`, headers: { Authorization: `Bearer ${session.token}` } }} style={styles.photo} />}
                <Text style={styles.rowTitle}>{entry.title}</Text>
                <Text style={styles.description}>{entry.placeName} · {entry.latitude.toFixed(4)}, {entry.longitude.toFixed(4)}</Text>
                <Text style={styles.description}>{entry.body}</Text>
              </Pressable>
            ))}
          </View>
        )}

        {screen === 'diaryComposer' && (
          <View style={[styles.card, styles.diaryCard]}>
            <Text style={styles.cardTitle}>Photo Diary Composer</Text>
            <Text style={styles.description}>Exactly one real image is required. EXIF is best effort; final coordinates are required before save.</Text>
            {photo && <Image source={{ uri: photo.uri }} style={styles.photo} />}
            <Action label="Pick exactly one photo" onPress={pickOnePhoto} disabled={busy} />
            <Field label="Title" value={draftTitle} onChangeText={setDraftTitle} />
            <Field label="Body" value={draftBody} onChangeText={setDraftBody} multiline />
            <Field label="Tags (comma separated)" value={draftTags} onChangeText={setDraftTags} />
            <Field label="Place label" value={draftPlace} onChangeText={setDraftPlace} />
            <View style={styles.split}>
              <Field label="Latitude" value={draftLat} onChangeText={(value) => { setDraftLat(value); setCoordinateProvenance('manual'); }} keyboardType="numeric" />
              <Field label="Longitude" value={draftLng} onChangeText={(value) => { setDraftLng(value); setCoordinateProvenance('manual'); }} keyboardType="numeric" />
            </View>
            <Action label="Use current location fallback" onPress={useCurrentLocation} secondary disabled={busy} />
            <Action label="Upload photo and save diary" onPress={createDiaryEntry} disabled={busy} />
          </View>
        )}

        {screen === 'diaryDetail' && (
          <View style={[styles.card, styles.diaryCard]}>
            <Text style={styles.cardTitle}>Photo Diary Detail</Text>
            {selectedEntry ? (
              <>
                <Text style={styles.detailTitle}>{selectedEntry.title}</Text>
                {session && <Image source={{ uri: `${apiBaseUrl}/api/v1/media/${selectedEntry.mediaId}/binary`, headers: { Authorization: `Bearer ${session.token}` } }} style={styles.photo} />}
                <Text style={styles.description}>{selectedEntry.body}</Text>
                <Text style={styles.mapBox}>Map coordinate: {selectedEntry.latitude}, {selectedEntry.longitude}</Text>
                <Text style={styles.description}>Tags: {selectedEntry.tags.join(', ') || 'none'} · Captured {new Date(selectedEntry.capturedAt).toLocaleString()}</Text>
                <Action label="Refresh this entry" onPress={() => run('Refreshing diary detail.', async () => { setSelectedEntry(await api<DiaryEntry>(`/api/v1/diary-entries/${selectedEntry.entryId}`)); })} />
              </>
            ) : <Text style={styles.description}>No diary entry selected.</Text>}
          </View>
        )}

        {screen === 'placesList' && (
          <View style={[styles.card, styles.placeCard]}>
            <Text style={styles.cardTitle}>Saved Places List</Text>
            <Text style={styles.description}>Places shared with you are saved automatically when trusted, or held for review when details are uncertain.</Text>
            <Action label="Refresh saved places" onPress={() => run('Refreshing saved places.', refreshPlaces)} />
            {places.map((placeItem) => (
              <Pressable key={placeItem.placeId} onPress={() => { setSelectedPlace(placeItem); setScreen('placeDetailInbox'); }} style={styles.placeItem}>
                <Text style={styles.rowTitle}>{placeItem.name}</Text>
                <Text style={styles.description}>{placeItem.category} · {placeItem.regionText} · {placeItem.visitStatus}</Text>
              </Pressable>
            ))}
          </View>
        )}

        {screen === 'placeDetailInbox' && (
          <View style={[styles.card, styles.placeCard]}>
            <Text style={styles.cardTitle}>Saved Place Detail & Unresolved Inbox</Text>
            <Text style={styles.segmentTitle}>Saved place detail</Text>
            {selectedPlace ? (
              <View style={styles.placeDetail}>
                <Text style={styles.detailTitle}>{selectedPlace.name}</Text>
                <Text style={styles.description}>{selectedPlace.address}</Text>
                <Text style={styles.description}>{selectedPlace.summary}</Text>
                <Text style={styles.mapBox}>Place coordinate: {selectedPlace.latitude ?? 'n/a'}, {selectedPlace.longitude ?? 'n/a'}</Text>
              </View>
            ) : <Text style={styles.description}>Select a saved place from the list.</Text>}
            <Text style={styles.segmentTitle}>Unresolved inbox</Text>
            <Action label="Refresh unresolved intake from pending shares" onPress={() => run('Refreshing unresolved inbox.', refreshUnresolvedFromPendingShares)} secondary disabled={busy} />
            {filterFreshPendingShares(pendingShares)
              .filter((item) => (!item.userId || item.userId === session?.user.userId)
                && (!placesFolder || !item.intendedFolderId || item.intendedFolderId === placesFolder.folderId))
              .map((item) => (
                <View key={item.clientIntakeId} style={styles.row}>
                  <Text style={styles.rowTitle}>{item.rawTitle || item.rawUrl || 'Locally retained share'}</Text>
                  <Text style={styles.description}>{item.retryState ?? 'retryable'} · {item.lastError ?? 'waiting for automatic retry'}</Text>
                  {!item.intendedFolderId && placesFolder && (!item.userId || item.userId === session?.user.userId) && (
                    <Action
                      label={item.userId ? 'Bind to this folder' : 'Confirm ownership and bind to this folder'}
                      onPress={() => run(
                        item.userId ? 'Binding owned share to this folder.' : 'Binding quarantined share after ownership confirmation.',
                        () => bindPendingShareToFolder(item.clientIntakeId, placesFolder.folderId),
                      )}
                      secondary
                      disabled={busy}
                    />
                  )}
                  {(item.retryState === 'validation' || item.retryState === 'conflict') && (
                    <Action label="Discard blocked local payload" onPress={() => discardLocalPendingShare(item.clientIntakeId)} danger disabled={busy} />
                  )}
                </View>
              ))}
            {unresolved.map((item) => (
              <Pressable key={item.intakeId} onPress={() => { setSelectedIntake(item); setRepairName(item.rawTitle); }} style={[styles.row, selectedIntake?.intakeId === item.intakeId && styles.rowActive]}>
                <Text style={styles.rowTitle}>{item.rawTitle || item.rawUrl || 'Untitled share'}</Text>
                <Text style={styles.description}>{item.status} · {item.failureReason ?? 'manual repair available'}</Text>
              </Pressable>
            ))}
            <Field label="Repair name" value={repairName} onChangeText={setRepairName} />
            <Field label="Category" value={repairCategory} onChangeText={setRepairCategory} />
            <Field label="Address" value={repairAddress} onChangeText={setRepairAddress} />
            <Field label="Region" value={repairRegion} onChangeText={setRepairRegion} />
            <View style={styles.split}>
              <Field label="Latitude" value={repairLat} onChangeText={setRepairLat} keyboardType="numeric" />
              <Field label="Longitude" value={repairLng} onChangeText={setRepairLng} keyboardType="numeric" />
            </View>
            <Action label="Repair unresolved into saved place" onPress={repairUnresolved} disabled={busy} />
          </View>
        )}
      </ScrollView>
    </View>
  );
}

function Segment({ values, value, onChange }: { values: string[]; value: string; onChange: (value: string) => void }) {
  return (
    <View style={styles.segment}>
      {values.map((item) => (
        <Pressable key={item} onPress={() => onChange(item)} style={[styles.segmentButton, item === value && styles.segmentActive]}>
          <Text style={[styles.segmentText, item === value && styles.segmentTextActive]}>{item}</Text>
        </Pressable>
      ))}
    </View>
  );
}

function Field(props: ComponentProps<typeof TextInput> & { label: string }) {
  const { label, ...inputProps } = props;
  return (
    <View style={styles.field}>
      <Text style={styles.label}>{label}</Text>
      <TextInput style={[styles.input, inputProps.multiline && styles.multiline]} placeholderTextColor="#98a2b3" {...inputProps} />
    </View>
  );
}

function Action({ label, onPress, secondary, danger, disabled }: { label: string; onPress: () => void; secondary?: boolean; danger?: boolean; disabled?: boolean }) {
  return (
    <Pressable disabled={disabled} onPress={onPress} style={[styles.button, secondary && styles.secondaryButton, danger && styles.dangerButton, disabled && styles.buttonDisabled]} accessibilityRole="button" accessibilityState={{ disabled }}>
      <Text style={[styles.buttonText, secondary && styles.secondaryButtonText]}>{label}</Text>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  app: { flex: 1, backgroundColor: '#f7f6f2' },
  nav: { maxHeight: 58, backgroundColor: '#ffffff', borderBottomColor: '#e4e7ec', borderBottomWidth: 1 },
  navContent: { padding: 10, gap: 8 },
  navPill: { paddingHorizontal: 14, paddingVertical: 9, borderRadius: 999, backgroundColor: '#f2f4f7' },
  navPillActive: { backgroundColor: '#172033' },
  diaryPill: { borderColor: '#f7b267', borderWidth: 1 },
  placePill: { borderColor: '#7aa7c7', borderWidth: 1 },
  navText: { color: '#344054', fontWeight: '800' },
  navTextActive: { color: '#ffffff' },
  container: { padding: 20, gap: 16, paddingBottom: 48 },
  header: { gap: 8 },
  eyebrow: { color: '#667085', fontWeight: '900', letterSpacing: 1, textTransform: 'uppercase' },
  title: { fontSize: 30, fontWeight: '900', color: '#172033' },
  description: { color: '#667085', fontSize: 15, lineHeight: 21 },
  status: { color: '#175cd3', fontWeight: '700' },
  card: { backgroundColor: '#ffffff', borderRadius: 24, padding: 18, gap: 14, borderColor: '#e4e7ec', borderWidth: 1 },
  diaryCard: { backgroundColor: '#fff8ef', borderColor: '#fed7aa' },
  placeCard: { backgroundColor: '#f0f7fb', borderColor: '#b9d7ea' },
  cardTitle: { fontSize: 22, fontWeight: '900', color: '#172033' },
  field: { gap: 6, flex: 1 },
  label: { color: '#344054', fontWeight: '800' },
  input: { borderColor: '#d0d5dd', borderWidth: 1, borderRadius: 14, padding: 12, backgroundColor: '#ffffff', color: '#172033' },
  multiline: { minHeight: 92, textAlignVertical: 'top' },
  split: { flexDirection: 'row', gap: 10 },
  button: { backgroundColor: '#172033', borderRadius: 999, padding: 14, alignItems: 'center' },
  buttonDisabled: { opacity: 0.45 },
  secondaryButton: { backgroundColor: '#ffffff', borderColor: '#172033', borderWidth: 1 },
  dangerButton: { backgroundColor: '#b42318' },
  buttonText: { color: '#ffffff', fontWeight: '900' },
  secondaryButtonText: { color: '#172033' },
  row: { padding: 14, borderRadius: 18, backgroundColor: '#f9fafb', gap: 4, borderWidth: 1, borderColor: '#eaecf0' },
  rowActive: { borderColor: '#175cd3', backgroundColor: '#eff8ff' },
  rowTitle: { color: '#172033', fontWeight: '900', fontSize: 17 },
  diaryItem: { padding: 14, borderRadius: 18, backgroundColor: '#ffffff', gap: 4, borderLeftColor: '#f97316', borderLeftWidth: 5 },
  placeItem: { padding: 14, borderRadius: 18, backgroundColor: '#ffffff', gap: 4, borderLeftColor: '#0ea5e9', borderLeftWidth: 5 },
  photo: { width: '100%', height: 260, borderRadius: 20, backgroundColor: '#111827' },
  detailTitle: { color: '#172033', fontWeight: '900', fontSize: 26 },
  mapBox: { color: '#172033', fontWeight: '800', backgroundColor: '#ffffff', borderRadius: 18, padding: 16, borderColor: '#d0d5dd', borderWidth: 1 },
  segment: { flexDirection: 'row', backgroundColor: '#f2f4f7', padding: 4, borderRadius: 999 },
  segmentButton: { flex: 1, padding: 10, borderRadius: 999, alignItems: 'center' },
  segmentActive: { backgroundColor: '#172033' },
  segmentText: { color: '#344054', fontWeight: '800' },
  segmentTextActive: { color: '#ffffff' },
  segmentTitle: { color: '#172033', fontWeight: '900', fontSize: 18, marginTop: 8 },
  placeDetail: { gap: 8, padding: 14, borderRadius: 18, backgroundColor: '#ffffff' },
});
