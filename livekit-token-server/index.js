// index.js - BASİTLEŞTİRİLMİŞ VE GARANTİLİ GRUP MANTIĞI
require('dotenv').config();
const express = require('express');
const { AccessToken } = require('livekit-server-sdk');
const admin = require('firebase-admin');

let firebaseReady = false;
try {
  const serviceAccount = require("./serviceAccountKey.json");
  admin.initializeApp({ credential: admin.credential.cert(serviceAccount) });
  firebaseReady = true;
  console.log("Firebase Admin SDK hazır.");
} catch (e) {
  console.log("UYARI: serviceAccountKey.json bulunamadı.");
}

const app = express();
app.use(express.json());

const API_KEY = process.env.LIVEKIT_API_KEY;
const API_SECRET = process.env.LIVEKIT_API_SECRET;
const PORT = 3005;

const users = {};
// Aktif odaları ve katılımcıları takip edelim
const activeRooms = {};

app.post('/register', (req, res) => {
  const { identity, password, fcmToken, profilePhoto } = req.body;
  users[identity] = { password, fcmToken, profilePhoto: profilePhoto || null, lastSeen: Date.now(), currentRoom: null };
  console.log(`Yeni Kayıt: ${identity}`);
  res.sendStatus(200);
});

app.post('/login', (req, res) => {
  const { identity, password, fcmToken } = req.body;
  if (users[identity]) {
    users[identity].fcmToken = fcmToken;
    users[identity].lastSeen = Date.now();
    users[identity].currentRoom = null; // Giriş yapınca kesin temizle
    console.log(`Giriş: ${identity} - Oda Temizlendi`);
    return res.sendStatus(200);
  }
  res.sendStatus(401);
});

app.post('/heartbeat', (req, res) => {
  const { identity, room } = req.body;
  if (identity && users[identity]) {
    users[identity].lastSeen = Date.now();

    // ÇOK KRİTİK: Gelen veri 'null', 'undefined' veya boş string ise odayı temizle
    if (!room || room === "" || room === "null" || room === undefined) {
        if (users[identity].currentRoom !== null) {
            console.log(`DURUM DEĞİŞTİ: ${identity} görüşmeden çıktı.`);
            users[identity].currentRoom = null;
        }
    } else {
        if (users[identity].currentRoom !== room) {
            console.log(`DURUM DEĞİŞTİ: ${identity} şu odaya girdi: ${room}`);
            users[identity].currentRoom = room;
        }
    }
  }
  res.sendStatus(200);
});

app.post('/offline', (req, res) => {
  const { identity } = req.body;
  if (identity && users[identity]) {
      users[identity].currentRoom = null;
  }
  res.sendStatus(200);
});

app.post('/update-user', (req, res) => {
  const { identity, profilePhoto } = req.body;

  if (!identity || !users[identity]) {
    return res.status(404).json({ error: 'Kullanıcı bulunamadı' });
  }

  users[identity].profilePhoto = profilePhoto;
  console.log(`Profil fotoğrafı güncellendi: ${identity}`);
  res.json({ success: true });
});

app.get('/users', (req, res) => {
  res.json(Object.keys(users).map(id => ({
    identity: id,
    isOnline: (Date.now() - (users[id].lastSeen || 0)) < 30000,
    profilePhoto: users[id].profilePhoto,
    currentRoom: users[id].currentRoom
  })));
});

app.get('/token', async (req, res) => {
  const { identity, target, room } = req.query;

  // Eğer room parametresi varsa o odaya sok, yoksa target bazlı oda ismi üret (veya test odası)
  let roomName = room || (target ? `ROOM_${[identity, ...target.split(',')].sort().join('_')}` : "LIVEKIT_TEST_ROOM");

  // Basitleştirme: Eğer target varsa ve bir odaya bağlı değillerse herkesi TEST odasına sokalım (eski mantık gibi)
  // Ama özel oda istersen yukarıdaki mantık daha iyi.
  // roomName = "LIVEKIT_TEST_ROOM";

  console.log(`[BAĞLANTI] ${identity} -> ${roomName} odasına yönlendiriliyor.`);
  users[identity].currentRoom = roomName;

  // Bildirim gönder (Tüm hedeflere)
  if (firebaseReady && target) {
    target.split(',').forEach(t => {
      if (users[t] && users[t].fcmToken) {
        admin.messaging().send({
          data: {
            caller: identity,
            room: roomName,
            start_call: "true"
          },
          notification: {
            title: "Gelen Arama",
            body: `${identity} seni arıyor...`
          },
          token: users[t].fcmToken,
          android: {
            priority: 'high',
            notification: {
              channelId: 'call_channel_v3',
              priority: 'max',
              visibility: 'public'
            }
          },
          apns: {
            payload: {
              aps: {
                contentAvailable: true,
                sound: "default"
              },
            },
            headers: {
              'apns-priority': '10',
            },
          }
        }).then(() => console.log(`Bildirim gitti: ${t}`))
          .catch(e => console.log(`Hata: ${t}`, e.message));
      }
    });
  }

  try {
    const at = new AccessToken(API_KEY, API_SECRET, { identity, ttl: '60m' });
    at.addGrant({ room: roomName, roomJoin: true, canPublish: true, canSubscribe: true });
    res.json({ token: await at.toJwt(), url: process.env.LIVEKIT_URL, room: roomName });
  } catch (err) {
    res.status(500).send("Token hatası");
  }
});

app.listen(PORT, '0.0.0.0', () => {
  console.log(`🚀 TEST MODU: http://0.0.0.0:${PORT} adresinde aktif.`);
});
