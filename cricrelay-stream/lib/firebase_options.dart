// Generated values — replace via `flutterfire configure` or CI secret
// FIREBASE_GOOGLE_SERVICES_JSON_BASE64 (see docs/PLAY_STORE.md).
import 'package:firebase_core/firebase_core.dart' show FirebaseOptions;
import 'package:flutter/foundation.dart'
    show defaultTargetPlatform, kIsWeb, TargetPlatform;

class DefaultFirebaseOptions {
  static FirebaseOptions get currentPlatform {
    if (kIsWeb) {
      throw UnsupportedError('CricRelay Stream does not support web.');
    }
    switch (defaultTargetPlatform) {
      case TargetPlatform.android:
        return android;
      case TargetPlatform.iOS:
        return ios;
      default:
        throw UnsupportedError(
          'Firebase is not configured for $defaultTargetPlatform.',
        );
    }
  }

  /// Placeholder — CI writes google-services.json; run `flutterfire configure` locally.
  static const FirebaseOptions android = FirebaseOptions(
    apiKey: 'REPLACE_ME',
    appId: '1:000000000000:android:0000000000000000000000',
    messagingSenderId: '000000000000',
    projectId: 'cricrelay-stream',
    storageBucket: 'cricrelay-stream.appspot.com',
  );

  static const FirebaseOptions ios = FirebaseOptions(
    apiKey: 'REPLACE_ME',
    appId: '1:000000000000:ios:0000000000000000000000',
    messagingSenderId: '000000000000',
    projectId: 'cricrelay-stream',
    storageBucket: 'cricrelay-stream.appspot.com',
    iosBundleId: 'uk.co.cricrelay.stream',
  );
}
