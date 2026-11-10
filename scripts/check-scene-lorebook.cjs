const fs = require('node:fs');
const assert = require('node:assert/strict');
const [originalPath, automaticPath, explicitPath, readerPath] = process.argv.slice(2);
if (!explicitPath) throw new Error('Pass original, automatic and explicit-cast lorebook paths.');
const read = path => JSON.parse(fs.readFileSync(path, 'utf8'));
const original = read(originalPath), automatic = read(automaticPath), explicit = read(explicitPath);
let checks = 0;
const test = condition => { assert.ok(condition); checks++; };
const expression = key => { const end = key.lastIndexOf('/'); return new RegExp(key.slice(1, end), key.slice(end + 1)); };
for (const output of [automatic, explicit]) {
  test(output.entries.length === original.entries.length);
  test(new Set(output.order).size === original.entries.length);
  for (let i = 0; i < original.entries.length; i++) {
    const source = original.entries[i], entry = output.entries[i];
    test(entry.id === source.id && entry.text === source.text);
    test(entry.contextConfig.tokenBudget > 1);
    if (!source.keys.length) { test(entry.forceActivation === source.forceActivation); continue; }
    test(entry.forceActivation === false && entry.nonStoryActivatable === false);
    expression(entry.keys[0]); checks++;
  }
}
for (let i = 0; i < original.entries.length; i++) {
  const source = original.entries[i];
  if (!source.id.startsWith('char_')) continue;
  const name = source.keys.find(key => !key.startsWith('char_'));
  const auto = expression(automatic.entries[i].keys[0]), strict = expression(explicit.entries[i].keys[0]);
  test(auto.test(`${name} entered the room.`));
  test(!auto.test(`${name} left.\n***\nThe empty room was quiet.`));
  test(!auto.test(`${name} left.\n[Scene: No-one]\nThe room was quiet.`));
  test(auto.test(`An earlier scene.\n---\n${name} entered.`));
  test(!auto.test(`Prefix${name}Suffix`));
  test(strict.test(`[Scene: ${name}]\nThe room was quiet.`));
  test(strict.test(`[Scene cast: ${name}, Another Person]\nThe room was quiet.`));
  test(!strict.test(`[Scene: Another Person]\nSomeone mentioned ${name} offstage.`));
  test(!strict.test(`[Scene: ${name}]\nOld room.\n⁂\nNew empty room.`));
  test(!auto.test(`${name} left.\n[Scene: ${'Other, '.repeat(70)}No-one]\nEmpty room.`));
  test(!strict.test(`[Scene: ${name}]\nOld room.\n[Scene: ${'Other, '.repeat(70)}No-one]\nEmpty room.`));
}
if (readerPath) {
  const reader = read(readerPath), originals = original.entries.filter(entry => entry.id.startsWith('char_'));
  test(reader.format === 'roadleaf-scene-profiles' && reader.version === 1 && reader.profileSetId.length > 0);
  test(reader.entries.length === originals.length);
  for (let i = 0; i < reader.entries.length; i++) {
    const entry = reader.entries[i];
    test(entry.id === originals[i].id && JSON.stringify(entry.keys) === JSON.stringify(originals[i].keys));
    test(!('text' in entry) && !('contextConfig' in entry) && !('loreBiasGroups' in entry));
  }
}
console.log(JSON.stringify({checks, entries: original.entries.length, characters: original.entries.filter(entry => entry.id.startsWith('char_')).length, textPreserved: true, liveNovelAiImportTested: false}));
