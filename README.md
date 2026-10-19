# Image Interest Tracker

A lightweight HTML + Java app for saving image inspirations in a searchable JSON database.

## Features

- Add image entries with title, URL, description, notes, and tags
- Search across titles, descriptions, notes, URLs, and tags
- Filter by tag
- Mark entries as favorites
- Delete entries
- Persist data in `data/images.json`

## Project structure

- `src/ImageTrackerServer.java` - Java HTTP server and JSON-backed API
- `static/index.html` - frontend structure
- `static/styles.css` - visual design
- `static/app.js` - browser-side logic
- `data/images.json` - JSON database file

## Run locally

1. Compile:

```powershell
javac -d out src/ImageTrackerServer.java
```

2. Start the server:

```powershell
java -cp out ImageTrackerServer
```

3. Open [http://localhost:8080](http://localhost:8080)

## API

- `GET /api/images?query=portrait&tag=lighting&favorite=true`
- `POST /api/images`
- `PUT /api/images/{id}/favorite`
- `DELETE /api/images/{id}`

## Flet prototype

There is also a clickable Python prototype at `flet_prototype.py` that uses colored squares as stand-ins for image thumbnails.

Run it with:

```powershell
python flet_prototype.py
```
