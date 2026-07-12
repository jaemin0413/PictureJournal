import Constants from 'expo-constants';
import * as Crypto from 'expo-crypto';
import * as ImagePicker from 'expo-image-picker';
import * as Linking from 'expo-linking';
import * as Location from 'expo-location';
import * as SecureStore from 'expo-secure-store';
import { StatusBar } from 'expo-status-bar';
import { useShareIntent } from 'expo-share-intent';
import type { ComponentProps } from 'react';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
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
};
type PickedPhoto = { uri: string; name: string; mimeType: string; latitude?: number; longitude?: number; takenAt?: string };

type JsonRecord = Record<string, unknown>;

const configuredApiBaseUrl =
  process.env.EXPO_PUBLIC_API_BASE_URL ??
  (Platform.OS === 'android'
    ? (Constants.expoConfig?.extra?.apiBaseUrlAndroidEmulator as string | undefined)
    : (Constants.expoConfig?.extra?.apiBaseUrl as string | undefined));
const apiBaseUrl = (configuredApiBaseUrl ?? 'http://localhost:8080').replace(/\/$/, '');
const sessionKey = 'picturejournal.session.v1';
const pendingShareKey = 'picturejournal.pendingShares.v1';
const diaryFolderKey = 'picturejournal.diaryFolder.v1';
const placesFolderKey = 'picturejournal.placesFolder.v1';
const pendingShareTtlMs = 24 * 60 * 60 * 1000;
const screens: { key: Screen; label: string; product: 'Core' | 'Photo Diary' | 'Saved Places' }[] = [
  { key: 'auth', label: '1 Auth', product: 'Core' },
  { key: 'folders', label: '2 Folder Select', product: 'Core' },
  { key: 'diaryFeed', label: '3 Photo Diary Feed', product: 'Photo Diary' },
  { key: 'diaryComposer', label: '4 Photo Diary Composer', product: 'Photo Diary' },
  { key: 'diaryDetail', label: '5 Photo Diary Detail', product: 'Photo Diary' },
  { key: 'placesList', label: '6 Saved Places List', product: 'Saved Places' },
  { key: 'placeDetailInbox', label: '7 Place Detail + Unresolved Inbox', product: 'Saved Places' },
];

function text(value: unknown): string {
  return typeof value === 'string' ? value : '';
}

async function createPendingShare(input: {
  rawUrl: string;
  rawTitle: string;
  rawText: string;
  sourceApp: string;
  platform: string;
  receivedVia: string;
}): Promise<PendingSharePayload> {
  const receivedAt = Date.now();
  const canonical = [input.rawUrl.trim().toLowerCase(), input.rawTitle.trim(), input.rawText.trim().slice(0, 512), input.sourceApp, input.platform].join('|');
  const contentFingerprint = await Crypto.digestStringAsync(Crypto.CryptoDigestAlgorithm.SHA256, canonical);
  const clientIntakeId = await Crypto.digestStringAsync(
    Crypto.CryptoDigestAlgorithm.SHA256,
    `${contentFingerprint}|${receivedAt}`,
  );
  return { ...input, receivedAt, contentFingerprint, clientIntakeId };
}

async function secureGet(key: string): Promise<string | null> {
  try {
    return await SecureStore.getItemAsync(key);
  } catch {
    return null;
  }
}

async function secureSet(key: string, value: string): Promise<void> {
  await SecureStore.setItemAsync(key, value, { keychainAccessible: SecureStore.AFTER_FIRST_UNLOCK_THIS_DEVICE_ONLY });
}

async function secureDelete(key: string): Promise<void> {
  try {
    await SecureStore.deleteItemAsync(key);
  } catch {
    // Missing secure storage should not block logout purging semantics on unsupported platforms.
  }
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
  const [email, setEmail] = useState('demo@picturejournal.local');
  const [displayName, setDisplayName] = useState('Picture Keeper');
  const [password, setPassword] = useState('password123');
  const [folders, setFolders] = useState<Folder[]>([]);
  const [folder, setFolder] = useState<Folder | null>(null);
  const [selectedDiaryFolderId, setSelectedDiaryFolderId] = useState<string | null>(null);
  const [selectedPlacesFolderId, setSelectedPlacesFolderId] = useState<string | null>(null);
  const diaryFolder =
    folders.find((item) => item.folderId === selectedDiaryFolderId && item.type === 'PHOTO_DIARY') ??
    folders.find((item) => item.type === 'PHOTO_DIARY') ??
    null;
  const placesFolder =
    folders.find((item) => item.folderId === selectedPlacesFolderId && item.type === 'REELS_PLACE') ??
    folders.find((item) => item.type === 'REELS_PLACE') ??
    null;
  const [entries, setEntries] = useState<DiaryEntry[]>([]);
  const [selectedEntry, setSelectedEntry] = useState<DiaryEntry | null>(null);
  const [places, setPlaces] = useState<SavedPlace[]>([]);
  const [selectedPlace, setSelectedPlace] = useState<SavedPlace | null>(null);
  const [unresolved, setUnresolved] = useState<ShareIntake[]>([]);
  const [selectedIntake, setSelectedIntake] = useState<ShareIntake | null>(null);
  const [pendingShares, setPendingShares] = useState<PendingSharePayload[]>([]);
  const pendingSharesRef = useRef<PendingSharePayload[]>([]);
  const [status, setStatus] = useState('Secure session restore pending.');
  const [busy, setBusy] = useState(false);
  const [draftTitle, setDraftTitle] = useState('A clear memory from today');
  const [draftBody, setDraftBody] = useState('What happened, who was there, and why this photo matters.');
  const [draftTags, setDraftTags] = useState('family, weekend');
  const [draftPlace, setDraftPlace] = useState('Pinned location');
  const [draftLat, setDraftLat] = useState('37.5665');
  const [draftLng, setDraftLng] = useState('126.9780');
  const [photo, setPhoto] = useState<PickedPhoto | null>(null);
  const [repairName, setRepairName] = useState('');
  const [repairAddress, setRepairAddress] = useState('');
  const [repairRegion, setRepairRegion] = useState('');
  const [repairLat, setRepairLat] = useState('');
  const [repairLng, setRepairLng] = useState('');
  const [repairCategory, setRepairCategory] = useState('saved');

  const authHeaders = useMemo(() => {
    const headers = new Headers();
    if (session) headers.set('Authorization', `Bearer ${session.token}`);
    return headers;
  }, [session]);

  const api = useCallback(
    async <T,>(path: string, options: RequestInit = {}): Promise<T> => {
      const headers = new Headers(options.headers);
      if (!(options.body instanceof FormData)) headers.set('Content-Type', 'application/json');
      if (session) headers.set('Authorization', `Bearer ${session.token}`);
      const response = await fetch(`${apiBaseUrl}${path}`, { ...options, headers });
      if (!response.ok) {
        const message = await response.text();
        throw new Error(`${response.status} ${message || response.statusText}`);
      }
      if (response.status === 204) return undefined as T;
      return (await response.json()) as T;
    },
    [session],
  );

  const persistPendingShares = useCallback(async (items: PendingSharePayload[]) => {
    const fresh = items.filter((item) => Date.now() - item.receivedAt < pendingShareTtlMs);
    pendingSharesRef.current = fresh;
    setPendingShares(fresh);
    await secureSet(pendingShareKey, JSON.stringify(fresh));
  }, []);

  const refreshFolders = useCallback(async () => {
    const data = await api<Folder[]>('/api/v1/folders');
    setFolders(data);
    if (!folder && data.length > 0) setFolder(data[0]);
    return data;
  }, [api, folder]);

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

  const refreshUnresolved = useCallback(async () => {
    if (!session || !placesFolder) return;
    const unresolvedItems: ShareIntake[] = [];
    const serverItems = await api<ShareIntake[]>(`/api/v1/folders/${placesFolder.folderId}/share-intake/unresolved`);
    unresolvedItems.push(...serverItems);
    const remaining: PendingSharePayload[] = [];
    for (const pending of pendingShares) {
      if (pending.userId && pending.userId !== session.user.userId) continue;
      if (pending.intendedFolderId && pending.intendedFolderId !== placesFolder.folderId) {
        remaining.push(pending);
        continue;
      }
      const share = { ...pending, userId: session.user.userId, intendedFolderId: placesFolder.folderId };
      try {
        const intake = await api<ShareIntake>('/api/v1/share-intake', {
          method: 'POST',
          body: JSON.stringify({
            folderId: placesFolder.folderId,
            clientIntakeId: share.clientIntakeId,
            contentFingerprint: share.contentFingerprint,
            rawUrl: share.rawUrl,
            rawTitle: share.rawTitle,
            rawText: share.rawText,
            sourceApp: share.sourceApp,
            platform: share.platform,
            receivedVia: share.receivedVia,
          }),
        });
        if (intake.status === 'RESOLVED' && intake.resolvedPlace) setSelectedPlace(intake.resolvedPlace);
        if (intake.status !== 'RESOLVED') unresolvedItems.push(intake);
      } catch {
        remaining.push(share);
        unresolvedItems.push({
          intakeId: share.clientIntakeId,
          folderId: placesFolder.folderId,
          rawUrl: share.rawUrl,
          rawTitle: share.rawTitle,
          rawText: share.rawText,
          status: 'LOCAL_PENDING',
          failureReason: 'Server intake failed; kept for manual repair.',
          candidates: [],
        });
      }
    }
    if (remaining.length !== pendingShares.length) await persistPendingShares(remaining);
    const merged = Array.from(new Map(unresolvedItems.map((item) => [item.intakeId, item])).values());
    setUnresolved(merged);
    setSelectedIntake((current) => current ?? merged[0] ?? null);
  }, [api, pendingShares, persistPendingShares, placesFolder, session]);

  useEffect(() => {
    const restore = async () => {
      const [storedSession, storedShares, storedDiaryFolderId, storedPlacesFolderId] = await Promise.all([
        secureGet(sessionKey),
        secureGet(pendingShareKey),
        secureGet(diaryFolderKey),
        secureGet(placesFolderKey),
      ]);
      if (storedShares) {
        const parsedShares = JSON.parse(storedShares) as PendingSharePayload[];
        await persistPendingShares(parsedShares);
      }
      setSelectedDiaryFolderId(storedDiaryFolderId);
      setSelectedPlacesFolderId(storedPlacesFolderId);
      if (!storedSession) {
        setStatus('Sign in or create an account to use the seven-screen MVP.');
        return;
      }
      const parsed = JSON.parse(storedSession) as Session;
      const response = await fetch(`${apiBaseUrl}/api/v1/auth/me`, {
        headers: { Authorization: `Bearer ${parsed.token}` },
      });
      if (!response.ok) {
        await Promise.all([secureDelete(sessionKey), secureDelete(pendingShareKey)]);
        setStatus('Stored session expired. Sign in again.');
        return;
      }
      const user = (await response.json()) as UserAccount;
      const validated = { token: parsed.token, user };
      setSession(validated);
      await secureSet(sessionKey, JSON.stringify(validated));
      setStatus('Secure session restored and validated.');
      setScreen('folders');
    };
    restore().catch((error: Error) => setStatus(error.message));
  }, [persistPendingShares]);

  useEffect(() => {
    const captureUrl = async (url: string | null) => {
      if (!url) return;
      const parsed = Linking.parse(url);
      const item = await createPendingShare({
        rawUrl: text(parsed.queryParams?.url),
        rawTitle: text(parsed.queryParams?.title),
        rawText: text(parsed.queryParams?.text),
        sourceApp: 'deep-link',
        platform: Platform.OS,
        receivedVia: Platform.OS === 'web' ? 'web_deeplink' : 'native_share',
      });
      await persistPendingShares([...pendingSharesRef.current, item]);
      console.info('PICTUREJOURNAL_SHARE_RECEIVED', item.clientIntakeId);
      setStatus('Share captured locally. Trusted normal share auto-save will run after auth and folder selection.');
      setScreen('placesList');
    };
    Linking.getInitialURL().then(captureUrl);
    const subscription = Linking.addEventListener('url', (event) => captureUrl(event.url));
    return () => subscription.remove();
  }, [persistPendingShares]);

  useEffect(() => {
    if (shareIntentError) setStatus(`Native share error: ${shareIntentError}`);
    if (!hasShareIntent) return;
    const captureNativeShare = async () => {
      const item = await createPendingShare({
        rawUrl: shareIntent.webUrl ?? '',
        rawTitle: shareIntent.meta?.title ?? '',
        rawText: shareIntent.text ?? '',
        sourceApp: 'native-share-sheet',
        platform: Platform.OS,
        receivedVia: 'native_share',
      });
      await persistPendingShares([...pendingSharesRef.current, item]);
      resetShareIntent(true);
      console.info('PICTUREJOURNAL_SHARE_RECEIVED', item.clientIntakeId);
      setStatus('Native share captured locally for automatic save after auth and folder binding.');
      setScreen('placesList');
    };
    captureNativeShare().catch((error: Error) => setStatus(error.message));
  }, [hasShareIntent, persistPendingShares, resetShareIntent, shareIntent, shareIntentError]);

  useEffect(() => {
    if (!session) return;
    refreshFolders().catch((error: Error) => setStatus(error.message));
  }, [session, refreshFolders]);

  useEffect(() => {
    if (!diaryFolder && !placesFolder) return;
    Promise.all([refreshDiary(), refreshPlaces(), refreshUnresolved()]).catch((error: Error) => setStatus(error.message));
  }, [diaryFolder, placesFolder, refreshDiary, refreshPlaces, refreshUnresolved]);

  const run = async (label: string, action: () => Promise<void>) => {
    setBusy(true);
    setStatus(label);
    try {
      await action();
    } catch (error) {
      setStatus(error instanceof Error ? error.message : 'Unexpected error');
    } finally {
      setBusy(false);
    }
  };

  const authenticate = () =>
    run(mode === 'signup' ? 'Creating account.' : 'Signing in.', async () => {
      if (mode === 'signup') await api<UserAccount>('/api/v1/auth/signup', { method: 'POST', body: JSON.stringify({ email, displayName, password }) });
      const next = await api<Session>('/api/v1/auth/login', { method: 'POST', body: JSON.stringify({ email, password }) });
      setSession(next);
      await secureSet(sessionKey, JSON.stringify(next));
      setScreen('folders');
      setStatus('Authenticated session securely persisted.');
    });

  const logout = () =>
    run('Logging out and purging local pending shares.', async () => {
      await Promise.all([
        secureDelete(sessionKey),
        secureDelete(pendingShareKey),
        secureDelete(diaryFolderKey),
        secureDelete(placesFolderKey),
      ]);
      setPendingShares([]);
      pendingSharesRef.current = [];
      setUnresolved([]);
      let warning: string | null = null;
      try {
        await api<void>('/api/v1/auth/logout', { method: 'POST' });
      } catch (error) {
        warning = error instanceof Error ? error.message : 'Backend logout failed';
      }
      setSession(null);
      setFolder(null);
      setSelectedDiaryFolderId(null);
      setSelectedPlacesFolderId(null);
      setScreen('auth');
      setStatus(warning ? `Local data purged; backend logout warning: ${warning}` : 'Logged out. Session and local pending share payloads purged.');
    });

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
      setFolder(created);
      if (type === 'PHOTO_DIARY') {
        setSelectedDiaryFolderId(created.folderId);
        await secureSet(diaryFolderKey, created.folderId);
      } else {
        setSelectedPlacesFolderId(created.folderId);
        await secureSet(placesFolderKey, created.folderId);
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
      const latitude = typeof exif.GPSLatitude === 'number' ? exif.GPSLatitude : undefined;
      const longitude = typeof exif.GPSLongitude === 'number' ? exif.GPSLongitude : undefined;
      setPhoto({
        uri: asset.uri,
        name: asset.fileName ?? `photo-${Date.now()}.jpg`,
        mimeType: asset.mimeType ?? 'image/jpeg',
        latitude,
        longitude,
        takenAt: text(exif.DateTimeOriginal) || undefined,
      });
      if (latitude !== undefined && longitude !== undefined) {
        setDraftLat(String(latitude));
        setDraftLng(String(longitude));
        setStatus('EXIF coordinates found. Review before saving.');
      } else {
        setStatus('No trusted EXIF coordinates found. Use current location or enter final coordinates before saving.');
      }
    });

  const useCurrentLocation = () =>
    run('Requesting current location for manual coordinate fallback.', async () => {
      const permission = await Location.requestForegroundPermissionsAsync();
      if (permission.status !== 'granted') throw new Error('Location permission denied. Enter latitude and longitude manually.');
      const current = await Location.getCurrentPositionAsync({ accuracy: Location.Accuracy.Balanced });
      setDraftLat(String(current.coords.latitude));
      setDraftLng(String(current.coords.longitude));
      setStatus('Current coordinates applied.');
    });

  const createDiaryEntry = () =>
    run('Uploading one photo and saving diary entry.', async () => {
      if (!diaryFolder) throw new Error('Create or select a Photo Diary folder first.');
      if (!photo) throw new Error('Photo Diary requires exactly one real image.');
      const latitude = Number(draftLat);
      const longitude = Number(draftLng);
      if (!Number.isFinite(latitude) || !Number.isFinite(longitude)) throw new Error('Final latitude and longitude are required before saving.');
      const form = new FormData();
      form.append('file', { uri: photo.uri, name: photo.name, type: photo.mimeType } as unknown as Blob);
      form.append('intendedFolderId', diaryFolder.folderId);
      const media = await api<MediaAsset>('/api/v1/media/direct-upload', { method: 'POST', headers: authHeaders, body: form });
      const created = await api<DiaryEntry>(`/api/v1/folders/${diaryFolder.folderId}/diary-entries`, {
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
      await refreshDiary();
      setSelectedEntry(created);
      setScreen('diaryDetail');
      setStatus('Photo Diary entry saved through media upload and re-query.');
    });

  const autoSaveNormalShare = () =>
    run('Auto-saving trusted share without confirmation.', async () => {
      if (!placesFolder) throw new Error('Create or select a Saved Places folder first.');
      const share = await createPendingShare({
        rawUrl: 'https://maps.example/place/cafe-onion',
        rawTitle: 'Cafe Onion Anguk',
        rawText: 'Cafe Onion Anguk, Seoul',
        sourceApp: 'manual-native-share',
        platform: Platform.OS,
        receivedVia: 'native_share',
      });
      const intake = await api<ShareIntake>('/api/v1/share-intake', {
        method: 'POST',
        body: JSON.stringify({
          folderId: placesFolder.folderId,
          clientIntakeId: share.clientIntakeId,
          contentFingerprint: share.contentFingerprint,
          rawUrl: share.rawUrl,
          rawTitle: share.rawTitle,
          rawText: share.rawText,
          sourceApp: share.sourceApp,
          platform: share.platform,
          receivedVia: share.receivedVia,
        }),
      });
      if (intake.resolvedPlace) {
        setSelectedPlace(intake.resolvedPlace);
        await refreshPlaces();
        setScreen('placeDetailInbox');
        setStatus('Normal share auto-saved. No confirmation UI was shown.');
      } else {
        setSelectedIntake(intake);
        setUnresolved((current) => [intake, ...current.filter((item) => item.intakeId !== intake.intakeId)]);
        setScreen('placeDetailInbox');
        setStatus('Share requires repair and was moved to the unresolved inbox.');
      }
    });

  const repairUnresolved = () =>
    run('Resolving unresolved share intake with manual repair.', async () => {
      if (!placesFolder || !selectedIntake) throw new Error('Choose an unresolved intake first.');
      const resolved = await api<{ intake: ShareIntake; savedPlace: SavedPlace }>(`/api/v1/share-intake/${selectedIntake.intakeId}/resolve`, {
        method: 'POST',
        body: JSON.stringify({
          folderId: placesFolder.folderId,
          candidateId: selectedIntake.candidates[0]?.candidateId ?? null,
          manualName: repairName || selectedIntake.rawTitle,
          category: repairCategory,
          address: repairAddress,
          regionText: repairRegion,
          latitude: Number(repairLat),
          longitude: Number(repairLng),
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
          <Pressable key={item.key} disabled={navDisabled} onPress={() => setScreen(item.key)} style={[styles.navPill, screen === item.key && styles.navPillActive, item.product === 'Photo Diary' && styles.diaryPill, item.product === 'Saved Places' && styles.placePill]}>
            <Text style={[styles.navText, screen === item.key && styles.navTextActive]}>{item.label}</Text>
          </Pressable>
        ))}
      </ScrollView>
      <ScrollView contentContainerStyle={styles.container}>
        <View style={styles.header}>
          <Text style={styles.eyebrow}>PictureJournal mobile MVP</Text>
          <Text style={styles.title}>{screens.find((item) => item.key === screen)?.label}</Text>
          <Text style={styles.description}>API: {apiBaseUrl} · {session ? `${session.user.displayName} signed in` : 'signed out'} · Diary: {diaryFolder?.name ?? 'none'} · Places: {placesFolder?.name ?? 'none'}</Text>
          <Text style={styles.status}>{busy ? 'Working… ' : ''}{status}</Text>
        </View>

        {screen === 'auth' && (
          <View style={styles.card}>
            <Text style={styles.cardTitle}>Complete auth and secure session persistence</Text>
            <Segment values={['login', 'signup']} value={mode} onChange={(value) => setMode(value as AuthMode)} />
            <Field label="Email" value={email} onChangeText={setEmail} autoCapitalize="none" />
            {mode === 'signup' && <Field label="Display name" value={displayName} onChangeText={setDisplayName} />}
            <Field label="Password" value={password} onChangeText={setPassword} secureTextEntry />
            <Action label={mode === 'signup' ? 'Create account and sign in' : 'Sign in'} onPress={authenticate} />
          </View>
        )}

        {screen === 'folders' && (
          <View style={styles.card}>
            <Text style={styles.cardTitle}>Folder Select</Text>
            <Text style={styles.description}>All diary, media, share, and place writes stay folder-scoped through the backend policy boundary.</Text>
            <Action label="Refresh folders" onPress={() => run('Refreshing folders.', async () => { await refreshFolders(); })} />
            {!diaryFolder && <Action label="Create Photo Diary folder" onPress={() => createFolder('PHOTO_DIARY')} secondary />}
            {!placesFolder && <Action label="Create Saved Places folder" onPress={() => createFolder('REELS_PLACE')} secondary />}
            {folders.map((item) => (
              <Pressable key={item.folderId} onPress={() => {
                setFolder(item);
                if (item.type === 'PHOTO_DIARY') {
                  setSelectedDiaryFolderId(item.folderId);
                  void secureSet(diaryFolderKey, item.folderId);
                }
                if (item.type === 'REELS_PLACE') {
                  setSelectedPlacesFolderId(item.folderId);
                  void secureSet(placesFolderKey, item.folderId);
                }
              }} style={[styles.row, (selectedDiaryFolderId === item.folderId || selectedPlacesFolderId === item.folderId) && styles.rowActive]}>
                <Text style={styles.rowTitle}>{item.name}</Text>
                <Text style={styles.description}>{item.type} · {item.role} · {item.description}</Text>
              </Pressable>
            ))}
            <Action label="Logout and purge pending shares" onPress={logout} danger />
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
            <Action label="Pick exactly one photo" onPress={pickOnePhoto} />
            <Field label="Title" value={draftTitle} onChangeText={setDraftTitle} />
            <Field label="Body" value={draftBody} onChangeText={setDraftBody} multiline />
            <Field label="Tags (comma separated)" value={draftTags} onChangeText={setDraftTags} />
            <Field label="Place label" value={draftPlace} onChangeText={setDraftPlace} />
            <View style={styles.split}>
              <Field label="Latitude" value={draftLat} onChangeText={setDraftLat} keyboardType="numeric" />
              <Field label="Longitude" value={draftLng} onChangeText={setDraftLng} keyboardType="numeric" />
            </View>
            <Action label="Use current location fallback" onPress={useCurrentLocation} secondary />
            <Action label="Upload photo and save diary" onPress={createDiaryEntry} />
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
                <Action label="Re-query this entry" onPress={() => run('Re-querying diary detail.', async () => { setSelectedEntry(await api<DiaryEntry>(`/api/v1/diary-entries/${selectedEntry.entryId}`)); })} />
              </>
            ) : <Text style={styles.description}>No diary entry selected.</Text>}
          </View>
        )}

        {screen === 'placesList' && (
          <View style={[styles.card, styles.placeCard]}>
            <Text style={styles.cardTitle}>Saved Places List</Text>
            <Text style={styles.description}>Cool utility-first place collection. Normal share auto-save has no confirmation step.</Text>
            <Action label="Refresh saved places" onPress={() => run('Refreshing saved places.', refreshPlaces)} />
            <Action label="Simulate normal share auto-save" onPress={autoSaveNormalShare} secondary />
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
            ) : <Text style={styles.description}>Select a saved place from screen 6.</Text>}
            <Text style={styles.segmentTitle}>Unresolved inbox</Text>
            <Action label="Refresh unresolved intake from pending shares" onPress={() => run('Refreshing unresolved inbox.', refreshUnresolved)} secondary />
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
            <Action label="Repair unresolved into saved place" onPress={repairUnresolved} />
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

function Action({ label, onPress, secondary, danger }: { label: string; onPress: () => void; secondary?: boolean; danger?: boolean }) {
  return (
    <Pressable onPress={onPress} style={[styles.button, secondary && styles.secondaryButton, danger && styles.dangerButton]} accessibilityRole="button">
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
