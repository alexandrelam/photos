import { createApp } from "./app";
import { loadConfig } from "./shared/config";

const config = loadConfig();
const { app, library } = createApp(config);

app.listen({ port: config.port, hostname: "0.0.0.0" });

console.log(`Darkroom server "${config.name}" listening on :${config.port}`);
console.log(`Photos root:  ${config.photosRoot}`);
console.log(`Uploads go to: ${config.photosRoot}/${config.uploadFolder}`);
console.log(`Access key:   ${config.accessKey}`);

library.rescan().then(
  (r) => console.log(`Indexed ${r.total} files (${r.added} new, ${r.updated} changed, ${r.removed} removed)`),
  (e) => console.error("Initial scan failed", e),
);
