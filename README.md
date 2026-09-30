# Studyroom

Desktop study-room prototype written entirely in Java 21. The interface is JavaFX; chat uses a small TCP peer-mesh transport where every desktop can listen for peers and broadcast messages directly.

## What is working

- PostgreSQL database for accounts and chat history
- Study-focused UI: conversations, shared room/presentation view, synced-music UI, profile
- Local TCP listener on port `5050` and peer-message protocol (`TcpChatNode`)
- Interactive message composer, music controls, view navigation, and video/presentation affordances

## PostgreSQL setup

Tạo database (sau khi cài PostgreSQL):

```sql
CREATE DATABASE studyroom;
```

`run.ps1` sẽ hỏi mật khẩu PostgreSQL ở mỗi lần chạy (ẩn ký tự), nên không cần lưu password trong source code. Nếu cần đổi URL/user mặc định, đặt biến môi trường:

```powershell
$env:STUDYROOM_DB_URL = "jdbc:postgresql://localhost:5432/studyroom"
$env:STUDYROOM_DB_USER = "postgres"
```

App tự tạo hai bảng `app_user` và `chat_message` ở lần kết nối đầu tiên.

## Run

Chạy trực tiếp trên máy hiện tại (Maven local đã được đặt trong `.tools`):

```powershell
.\run.ps1
```

Hoặc, nếu đã cài Maven hệ thống, dùng `mvn javafx:run`.

Để nối máy thứ hai trong cùng mạng LAN, chạy máy chủ phòng trước, sau đó chạy máy còn lại với IP của máy chủ:

```powershell
mvn javafx:run "-Dstudyroom.peer=192.168.1.10:5050"
```

## Production roadmap

1. Add TLS and a real identity service when deploying beyond a trusted network.
2. Add peer discovery / invite codes, authenticated room membership, encrypted signalling.
3. Use WebRTC (or an SFU) for camera, microphone, screen share and synchronized media. TCP alone is a poor transport for real-time video because head-of-line blocking causes visible stutter.
4. Add CRDT-backed collaborative whiteboard and slide-control messages to the same room protocol.
