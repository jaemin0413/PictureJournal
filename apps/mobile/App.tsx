import Constants from 'expo-constants';
import * as Linking from 'expo-linking';
import { StatusBar } from 'expo-status-bar';
import { useEffect, useMemo, useState } from 'react';
import { Platform, Pressable, ScrollView, StyleSheet, Text, TextInput, View } from 'react-native';

type SharePayload = {
  rawUrl: string;
  rawTitle: string;
  rawText: string;
  sourceApp: string;
  platform: string;
  receivedVia: string;
};

const apiBaseUrl = (Constants.expoConfig?.extra?.apiBaseUrl as string | undefined) ?? 'http://localhost:8080';

export default function App() {
  const [payload, setPayload] = useState<SharePayload>({
    rawUrl: '',
    rawTitle: 'Cafe Onion',
    rawText: 'place: Cafe Onion',
    sourceApp: 'manual-proof',
    platform: Platform.OS,
    receivedVia: Platform.OS === 'web' ? 'web_proof' : 'native_share',
  });
  const [lastUrl, setLastUrl] = useState<string>('');

  useEffect(() => {
    const applyUrl = (url: string | null) => {
      if (!url) return;
      setLastUrl(url);
      const parsed = Linking.parse(url);
      const sharedText = typeof parsed.queryParams?.text === 'string' ? parsed.queryParams.text : '';
      const sharedUrl = typeof parsed.queryParams?.url === 'string' ? parsed.queryParams.url : '';
      const sharedTitle = typeof parsed.queryParams?.title === 'string' ? parsed.queryParams.title : '';
      setPayload((current) => ({
        ...current,
        rawText: sharedText || current.rawText,
        rawUrl: sharedUrl || current.rawUrl,
        rawTitle: sharedTitle || current.rawTitle,
        receivedVia: Platform.OS === 'web' ? 'web_deeplink' : 'native_share',
      }));
    };

    Linking.getInitialURL().then(applyUrl);
    const subscription = Linking.addEventListener('url', (event) => applyUrl(event.url));
    return () => subscription.remove();
  }, []);

  const curlPreview = useMemo(() => {
    return `curl -X POST ${apiBaseUrl}/api/v1/share-intake \\\n  -H "Authorization: Bearer <token>" \\\n  -H "Content-Type: application/json" \\\n  -d '${JSON.stringify(payload)}'`;
  }, [payload]);

  return (
    <ScrollView contentContainerStyle={styles.container}>
      <StatusBar style="dark" />
      <Text style={styles.eyebrow}>G002 Mobile Proof Harness</Text>
      <Text style={styles.title}>Picture Journal Share Intake Proof</Text>
      <Text style={styles.description}>
        This screen runs on web, Android, and iOS. Web validates shared UI/API payload shape; Android and iOS native proof validates the actual share receiver entry.
      </Text>

      <View style={styles.card}>
        <Text style={styles.cardTitle}>Detected launch URL</Text>
        <Text style={styles.mono}>{lastUrl || 'No deep link captured yet.'}</Text>
      </View>

      <View style={styles.card}>
        <Text style={styles.cardTitle}>Share payload</Text>
        {(['rawUrl', 'rawTitle', 'rawText', 'sourceApp', 'platform', 'receivedVia'] as const).map((field) => (
          <View key={field} style={styles.field}>
            <Text style={styles.label}>{field}</Text>
            <TextInput
              style={styles.input}
              value={payload[field]}
              onChangeText={(value) => setPayload((current) => ({ ...current, [field]: value }))}
              multiline={field === 'rawText'}
            />
          </View>
        ))}
      </View>

      <View style={styles.card}>
        <Text style={styles.cardTitle}>Backend request preview</Text>
        <Text style={styles.mono}>{curlPreview}</Text>
      </View>

      <Pressable style={styles.button} accessibilityRole="button">
        <Text style={styles.buttonText}>Proof harness ready</Text>
      </Pressable>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  container: { padding: 24, gap: 16, backgroundColor: '#fbfaff', minHeight: '100%' },
  eyebrow: { color: '#4fb99c', fontWeight: '800', letterSpacing: 1, textTransform: 'uppercase' },
  title: { fontSize: 32, fontWeight: '900', color: '#172033' },
  description: { color: '#667085', fontSize: 16 },
  card: { backgroundColor: 'white', borderColor: '#ded6ff', borderWidth: 1, borderRadius: 20, padding: 16, gap: 10 },
  cardTitle: { fontSize: 18, fontWeight: '800', color: '#172033' },
  field: { gap: 6 },
  label: { color: '#667085', fontWeight: '700' },
  input: { borderColor: '#c9f3e6', borderWidth: 1, borderRadius: 12, padding: 10, backgroundColor: '#fbfffd' },
  mono: { fontFamily: Platform.select({ ios: 'Menlo', android: 'monospace', default: 'monospace' }), color: '#344054' },
  button: { backgroundColor: '#8b7cf6', borderRadius: 999, padding: 14, alignItems: 'center' },
  buttonText: { color: 'white', fontWeight: '900' },
});
