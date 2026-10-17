CREATE TABLE `media` (
	`id` integer PRIMARY KEY AUTOINCREMENT NOT NULL,
	`path` text NOT NULL,
	`folder` text NOT NULL,
	`name` text NOT NULL,
	`kind` text NOT NULL,
	`size` integer NOT NULL,
	`mtime_ms` integer NOT NULL,
	`taken_at` integer NOT NULL,
	`width` integer,
	`height` integer
);
--> statement-breakpoint
CREATE UNIQUE INDEX `media_path_idx` ON `media` (`path`);--> statement-breakpoint
CREATE INDEX `media_folder_idx` ON `media` (`folder`);--> statement-breakpoint
CREATE INDEX `media_taken_at_idx` ON `media` (`taken_at`,`id`);--> statement-breakpoint
CREATE TABLE `uploads` (
	`id` integer PRIMARY KEY AUTOINCREMENT NOT NULL,
	`device_id` text NOT NULL,
	`source_key` text NOT NULL,
	`path` text NOT NULL,
	`size` integer NOT NULL,
	`uploaded_at` integer NOT NULL
);
--> statement-breakpoint
CREATE UNIQUE INDEX `uploads_source_idx` ON `uploads` (`device_id`,`source_key`);--> statement-breakpoint
CREATE INDEX `uploads_path_idx` ON `uploads` (`path`);