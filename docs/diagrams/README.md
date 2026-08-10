# README diagrams

The diagrams in `../assets/` are generated artifacts. Edit the `.mmd` sources in this directory and render them with the pinned Mermaid CLI instead of editing the SVG or PNG files by hand. README pages display the PNG previews and link them to the corresponding high-resolution SVG files.

```powershell
npm ci
npm run install:browser
npm run render
```

`@mermaid-js/mermaid-cli` uses Puppeteer. The explicit `install:browser` step installs the compatible Chrome Headless Shell build used by `mmdc`; it also makes clean-machine and CI behavior reproducible. Keep `PUPPETEER_SKIP_DOWNLOAD` unset. If the browser cache is managed centrally, provide the corresponding Puppeteer cache configuration before installing the browser.
