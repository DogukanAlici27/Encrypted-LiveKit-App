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
const activeRooms = {};
const groups = {}; // Yeni: Grupları saklamak için
const messageStatus = {}; // Yeni: Mesajların iletildi/okundu durumlarını tutmak için

const ONLINE_THRESHOLD_MS = 40000;
 
const isUserOnline = (identity) => {
  if (!users[identity]) return false;
  return (Date.now() - (users[identity].lastSeen || 0)) < ONLINE_THRESHOLD_MS;
};
 
// Yardımcı: A kullanıcısı B'yi engelliyor mu?
// Yani B, A'nın blockedUsers listesinde mi?
const isBlockedBy = (blocker, target) => {
  if (!users[blocker]) return false;
  return (users[blocker].blockedUsers || []).includes(target);
};
 
app.post('/register', (req, res) => {
  const { identity, password, fcmToken, profilePhoto, publicKey, isOnline, blockedUsers } = req.body;

  // Boş/eksik identity "undefined" adlı hayalet kullanıcı yaratıyordu
  if (!identity || typeof identity !== 'string' || !identity.trim()) {
    return res.status(400).send("identity gerekli.");
  }
  if (!password || typeof password !== 'string') {
    return res.status(400).send("password gerekli.");
  }

  if (users[identity]) {
    console.log(`Kayıt Reddedildi: ${identity} zaten mevcut.`);
    return res.status(409).send("Bu kullanıcı adı zaten alınmış.");
  }
 
  const initialLastSeen = (isOnline === true || isOnline === undefined) ? Date.now() : 0;
 
  users[identity] = {
    password,
    fcmToken,
    profilePhoto: profilePhoto || null,
    publicKey: publicKey || null,
    lastSeen: initialLastSeen,
    currentRoom: null,
    blockedUsers: (blockedUsers && Array.isArray(blockedUsers)) ? blockedUsers : []
  };
  console.log(`Yeni Kayıt: ${identity} (Online: ${isOnline !== false})`);
  res.sendStatus(200);
});
 
app.post('/login', (req, res) => {
  const { identity, password, fcmToken, publicKey } = req.body;

  if (!identity || typeof identity !== 'string' || !identity.trim()) {
    return res.status(400).send("identity gerekli.");
  }

  if (isUserOnline(identity)) {
    console.log(`Giriş Reddedildi: ${identity} zaten başka bir cihazda aktif.`);
    return res.status(403).send("Bu hesap şu an başka bir cihazda aktif.");
  }

  if (users[identity]) {
    if (users[identity].password !== password) {
      console.log(`Giriş Reddedildi: ${identity} için şifre hatalı.`);
      return res.status(401).send("Şifre hatalı.");
    }
    users[identity].fcmToken = fcmToken;
    users[identity].lastSeen = Date.now();
    users[identity].currentRoom = null;
    if (publicKey) users[identity].publicKey = publicKey;
    console.log(`Giriş: ${identity} - Oda Temizlendi`);
    return res.sendStatus(200);
  }
  res.sendStatus(401);
});
 
app.post('/heartbeat', (req, res) => {
  const { identity, room } = req.body;
  if (identity && users[identity]) {
    users[identity].lastSeen = Date.now();
 
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
    res.status(200).json({
        received: true,
        threshold: ONLINE_THRESHOLD_MS
    });
  } else {
    res.status(401).send("Kullanıcı sunucu hafızasında yok.");
  }
});
 
app.post('/offline', (req, res) => {
  const { identity } = req.body;
  if (identity && users[identity]) {
      users[identity].currentRoom = null;
      users[identity].lastSeen = 0;
      console.log(`Çevrimdışı Sinyali: ${identity}`);
  }
  res.sendStatus(200);
});
 
app.post('/delete-user', (req, res) => {
  const { identity } = req.body;
  if (identity && users[identity]) {
    delete users[identity];
    console.log(`Kullanıcı Kalıcı Olarak Silindi (Sunucu): ${identity}`);
    return res.json({ success: true });
  }
  res.status(404).json({ error: 'Kullanıcı bulunamadı' });
});
 
app.post('/change-password', (req, res) => {
  const { identity, oldPassword, newPassword } = req.body;
  if (users[identity]) {
    if (users[identity].password === oldPassword) {
      users[identity].password = newPassword;
      console.log(`Şifre Değiştirildi: ${identity}`);
      return res.json({ success: true });
    } else {
      return res.status(401).json({ error: 'Eski şifre hatalı' });
    }
  }
  res.status(404).json({ error: 'Kullanıcı bulunamadı' });
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
  const requesterIdentity = req.query.identity;
  const requester = users[requesterIdentity];
 
  res.json(Object.keys(users).map(id => {
    const user = users[id];
    // Kritik Gizlilik: Eğer liste elemanı (id), listeyi isteyeni (requesterIdentity) engellemişse bilgileri gizle
    const hasBlockedRequester = (user.blockedUsers || []).includes(requesterIdentity);

    return {
      identity: id,
      isOnline: hasBlockedRequester ? false : (Date.now() - (user.lastSeen || 0)) < ONLINE_THRESHOLD_MS,
      profilePhoto: user.profilePhoto,
      currentRoom: hasBlockedRequester ? null : user.currentRoom,
      publicKey: user.publicKey || null,
      isBlocked: requester ? (requester.blockedUsers || []).includes(id) : false
    };
  }));
});
 
// -------------------------------------------------------------------
// ENGELLEME ENDPOINTLERİ
// -------------------------------------------------------------------
 
// Engelle / engeli kaldır
app.post('/block-user', (req, res) => {
  const { myIdentity, targetIdentity, isBlocked } = req.body;
  if (!users[myIdentity]) return res.status(404).send("Kullanıcı bulunamadı");
 
  if (!users[myIdentity].blockedUsers) users[myIdentity].blockedUsers = [];
 
  if (isBlocked) {
    if (!users[myIdentity].blockedUsers.includes(targetIdentity)) {
      users[myIdentity].blockedUsers.push(targetIdentity);
    }
  } else {
    users[myIdentity].blockedUsers = users[myIdentity].blockedUsers.filter(u => u !== targetIdentity);
  }
  console.log(`ENGELLEME: ${myIdentity} -> ${targetIdentity} (${isBlocked ? 'ENGELLEDİ' : 'KALDIRDI'})`);
  res.json({ success: true });
});
 
// Kendi blok listemi çek — Android açılışta bunu çağırıp local DB'yi günceller
// GET /my-blocks?identity=ahmet  →  ["mehmet", "ayse"]
app.get('/my-blocks', (req, res) => {
  const { identity } = req.query;
  if (!identity || !users[identity]) {
    return res.status(404).json({ error: 'Kullanıcı bulunamadı' });
  }
  const blocked = users[identity].blockedUsers || [];
  console.log(`BLOK LİSTESİ İSTEĞİ: ${identity} -> ${blocked.length} engel`);
  res.json({ blockedUsers: blocked });
});
 
// -------------------------------------------------------------------
 
app.post('/bulk-restore', (req, res) => {
  const { myIdentity, users: incomingUsers } = req.body;
 
  if (incomingUsers && Array.isArray(incomingUsers)) {
    incomingUsers.forEach(u => {
      if (!users[u.identity]) {
        users[u.identity] = {
          identity: u.identity,
          profilePhoto: u.profilePhoto || null,
          publicKey: u.publicKey || null,
          lastSeen: 0,
          currentRoom: null,
          blockedUsers: []
        };
      }
 
      if (u.isBlocked && myIdentity && users[myIdentity]) {
        if (!users[myIdentity].blockedUsers.includes(u.identity)) {
          users[myIdentity].blockedUsers.push(u.identity);
        }
      }
    });
    console.log(`TOPLU RESTORE: ${myIdentity} tarafından ${incomingUsers.length} kullanıcı işlendi.`);
  }
  res.json({ success: true });
});
 
app.get('/token', async (req, res) => {
  const { identity, target, room, keys } = req.query;
 
  // Kimlik kontrolü
  if (!identity) {
    return res.status(400).send("identity parametresi eksik.");
  }
 
  // Kullanıcı sunucu hafızasında yoksa 401 dön — Android silentSignIn tetikler
  if (!users[identity]) {
    console.log(`[TOKEN] ${identity} sunucu hafızasında yok, 401 dönülüyor.`);
    return res.status(401).send("Kullanıcı bulunamadı, lütfen tekrar giriş yapın.");
  }
 
  // Hedef listesini temizle (boş string, null vs. filtrele)
  const targetList = target
    ? target.split(',').map(t => t.trim()).filter(t => t.length > 0)
    : [];
 
  let roomName = room || (targetList.length > 0
    ? `ROOM_${[identity, ...targetList].sort().join('_')}`
    : "LIVEKIT_TEST_ROOM");
 
  console.log(`[BAĞLANTI] ${identity} -> ${roomName} odasına yönlendiriliyor.`);
 
  // -------------------------------------------------------------------
  // ENGEL KONTROLÜ — SUNUCU TARAFLI
  // Hedef kullanıcıların herhangi biri arayanı (identity) engellemiş mi?
  // Engellenmişse: o hedef için FCM gönderilmez ve arayan bilgilendirilir.
  // -------------------------------------------------------------------
  const blockedTargets = [];
 
  for (const t of targetList) {
    if (isBlockedBy(t, identity)) {
      blockedTargets.push(t);
      console.log(`[ENGEL] ${t} kişisi ${identity}'yi engellemiş, FCM gönderilmiyor.`);
    }
  }
 
  users[identity].currentRoom = roomName;
 
  let encryptedKeysMap = {};
  if (keys) {
    try { encryptedKeysMap = JSON.parse(keys); } catch (e) { encryptedKeysMap = {}; }
  }
  console.log(`[E2EE_DEBUG] identity=${identity} için gelen keys parametresi:`, keys);
  console.log(`[E2EE_DEBUG] parse edilmiş encryptedKeysMap:`, encryptedKeysMap);
 
  const busyTargets = [];
  for (const t of targetList) {
    if (users[t] && users[t].currentRoom && users[t].currentRoom !== roomName) {
      busyTargets.push(t);
    }
  }
 
  // Bildirim gönder — SADECE engellemeyenlere
  if (firebaseReady && targetList.length > 0) {
    for (const t of targetList) {
      // Bu kişi arayanı engellemiş mi? Engellemiş ise hiç bildirim gitmesin
      if (blockedTargets.includes(t)) continue;
 
      if (users[t] && users[t].fcmToken) {
        const targetIsBusy = busyTargets.includes(t);
        const encryptedRoomKey = encryptedKeysMap[t] || "";
        console.log(`[E2EE_DEBUG] hedef=${t}, encryptedRoomKey uzunluk=${encryptedRoomKey.length}`);
 
        admin.messaging().send({
          data: {
            caller: identity,
            room: roomName,
            start_call: "true",
            targetBusy: targetIsBusy ? "true" : "false",
            encryptedRoomKey: encryptedRoomKey
          },
          notification: {
            title: targetIsBusy ? "Kaçan Arama" : "Gelen Arama",
            body: targetIsBusy
              ? `${identity} seni aradı (görüşmedeydin)`
              : `${identity} seni arıyor...`
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
        }).then(() => console.log(`Bildirim gitti: ${t} (meşgul: ${targetIsBusy})`))
          .catch(e => console.log(`Hata: ${t}`, e.message));
      }
    }
  }
 
  try {
    const at = new AccessToken(API_KEY, API_SECRET, { identity, ttl: '60m' });
    at.addGrant({ room: roomName, roomJoin: true, canPublish: true, canSubscribe: true });
    res.json({ token: await at.toJwt(), url: process.env.LIVEKIT_URL, room: roomName, busyTargets, blockedTargets });
  } catch (err) {
    console.error(`[TOKEN HATA] ${err.message}`);
    res.status(500).send("Token hatası: " + err.message);
  }
});
 
 
 
// -------------------------------------------------------------------
// MESAJLAŞMA ENDPOINTLERİ
// -------------------------------------------------------------------

app.post('/create-group', (req, res) => {
  const { name, members, owner } = req.body;
  if (!name || !members || !Array.isArray(members) || !owner) {
    return res.status(400).send("Eksik parametre.");
  }

  const groupId = `GROUP_${Date.now()}_${Math.floor(Math.random() * 1000)}`;
  groups[groupId] = {
    id: groupId,
    name: name,
    members: members, // members listesinde owner da olmalı
    owner: owner,
    createdAt: Date.now()
  };

  console.log(`Grup Oluşturuldu: ${name} (${groupId}) - Üyeler: ${members.join(', ')}`);
  res.json({ groupId: groupId });
});

// YENİ: Grup Detaylarını Çekme
app.get('/group-details', (req, res) => {
  const { groupId } = req.query;
  if (!groupId || !groups[groupId]) {
    return res.status(404).send("Grup bulunamadı.");
  }
  res.json(groups[groupId]);
});

// YENİ: Yöneticilik Devretme
app.post('/transfer-admin', (req, res) => {
  const { groupId, currentAdmin, newAdmin } = req.body;
  if (!groups[groupId]) return res.status(404).send("Grup bulunamadı");

  if (groups[groupId].owner !== currentAdmin) {
    return res.status(403).send("Sadece mevcut yönetici yetki devredebilir.");
  }

  groups[groupId].owner = newAdmin;
  console.log(`YÖNETİCİ DEVRİ: ${groupId} -> Yeni Yönetici: ${newAdmin}`);

  // Gruptakilere bildirim gönderilebilir (opsiyonel, şimdilik log basıyoruz)
  res.json({ success: true });
});

app.post('/send-group-message', (req, res) => {
  const { groupId, sender, content } = req.body;

  if (!groupId || !sender || !content) {
    return res.status(400).send("Eksik parametre.");
  }

  const group = groups[groupId];
  if (!group) {
    return res.status(404).send("Grup bulunamadı.");
  }

  const serverMsgId = `MSG_${Date.now()}_${Math.floor(Math.random() * 1000000)}`;
  messageStatus[serverMsgId] = {};

  // Mesaj durumlarını sadece göndereni engelleNMEMİŞ üyeler için hazırla
  group.members.forEach(m => {
    if (m !== sender && !isBlockedBy(m, sender)) {
      messageStatus[serverMsgId][m] = { delivered: null, read: null };
    }
  });

  if (firebaseReady) {
    const promises = group.members
      .filter(m => m !== sender)
      .filter(m => !isBlockedBy(m, sender)) // Engellemiş olanlara BİLDİRİM GİTMESİN
      .map(memberId => {
        const member = users[memberId];
        if (member && member.fcmToken) {
          return admin.messaging().send({
            data: {
              type: "GROUP_CHAT_MESSAGE",
              groupId: groupId,
              groupName: group.name,
              sender: sender,
              content: content,
              serverMsgId: serverMsgId,
              timestamp: Date.now().toString()
            },
            token: member.fcmToken,
            android: { priority: 'high' }
          }).catch(e => console.error(`${memberId} için FCM hatası:`, e.message));
        }
        return Promise.resolve();
      });

    Promise.all(promises).then(() => {
      console.log(`Grup Mesajı: ${sender} -> ${group.name} (${groupId}) - ID: ${serverMsgId}`);
      res.json({ success: true, serverMsgId: serverMsgId });
    });
  } else {
    res.status(503).send("Firebase hazır değil.");
  }
});

app.post('/send-message', (req, res) => {
  const { sender, recipient, content } = req.body;

  if (!sender || !recipient || !content) {
    return res.status(400).send("Eksik parametre.");
  }

  if (!users[recipient]) {
    return res.status(404).send("Alıcı bulunamadı.");
  }

  const recipientToken = users[recipient].fcmToken;
  if (!recipientToken) {
    return res.status(404).send("Alıcının bildirim token'ı yok.");
  }

  const serverMsgId = `MSG_${Date.now()}_${Math.floor(Math.random() * 1000000)}`;

  // Eğer alıcı göndereni engellediyse, bildirim GÖNDERME ama başarılı dön
  if (isBlockedBy(recipient, sender)) {
    console.log(`[ENGEL] ${recipient} kişisi ${sender}'yı engellemiş, mesaj iletilmiyor (Sessiz Başarı)`);
    return res.json({ success: true, serverMsgId: serverMsgId });
  }

  messageStatus[serverMsgId] = {};
  messageStatus[serverMsgId][recipient] = { delivered: null, read: null };

  if (firebaseReady) {
    admin.messaging().send({
      data: {
        type: "CHAT_MESSAGE",
        sender: sender,
        recipient: recipient,
        content: content,
        serverMsgId: serverMsgId,
        timestamp: Date.now().toString()
      },
      token: recipientToken,
      android: {
        priority: 'high'
      }
    }).then(() => {
      console.log(`Mesaj gönderildi: ${sender} -> ${recipient} - ID: ${serverMsgId}`);
      res.json({ success: true, serverMsgId: serverMsgId });
    }).catch(e => {
      console.error(`Mesaj iletim hatası:`, e);
      res.status(500).send("Mesaj iletilemedi.");
    });
  } else {
    res.status(503).send("Firebase hazır değil.");
  }
});

app.post('/mark-read', (req, res) => {
  const { me, sender } = req.body;

  if (!me || !sender) {
    return res.status(400).send("Eksik parametre.");
  }

  if (!users[sender]) {
    return res.status(404).send("Gönderen bulunamadı.");
  }

  const senderToken = users[sender].fcmToken;
  if (!senderToken) {
    return res.sendStatus(200);
  }

  if (firebaseReady) {
    admin.messaging().send({
      data: {
        type: "READ_RECEIPT",
        reader: me
      },
      token: senderToken,
      android: {
        priority: 'high'
      }
    }).then(() => {
      console.log(`Okundu Sinyali: ${me} -> ${sender} (Okundu)`);
      res.json({ success: true });
    }).catch(e => {
      console.error(`Okundu sinyali iletim hatası:`, e);
      res.status(500).send("Sinyal iletilemedi.");
    });
  } else {
    res.status(503).send("Firebase hazır değil.");
  }
});

// YENİ: Mesaj durum raporlama (iletildi/okundu)
app.post('/report-status', (req, res) => {
  const { serverMsgId, identity, status } = req.body;
  if (!serverMsgId || !identity || !status) return res.status(400).send("Eksik veri");

  if (!messageStatus[serverMsgId]) {
    messageStatus[serverMsgId] = {};
  }
  if (!messageStatus[serverMsgId][identity]) {
    messageStatus[serverMsgId][identity] = { delivered: null, read: null };
  }

  if (status === 'delivered' && !messageStatus[serverMsgId][identity].delivered) {
    messageStatus[serverMsgId][identity].delivered = Date.now();
  } else if (status === 'read' && !messageStatus[serverMsgId][identity].read) {
    messageStatus[serverMsgId][identity].read = Date.now();
  }

  res.json({ success: true });
});

// YENİ: Mesaj durumunu sorgulama
app.get('/message-status', (req, res) => {
  const { serverMsgId } = req.query;
  if (!serverMsgId) return res.status(400).send("Eksik ID");

  const status = messageStatus[serverMsgId] || {};
  res.json(status);
});

app.listen(PORT, '0.0.0.0', () => {
  console.log(`🚀 TEST MODU: http://0.0.0.0:${PORT} adresinde aktif.`);
});
 
