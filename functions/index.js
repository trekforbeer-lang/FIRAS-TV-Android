const { onRequest } = require("firebase-functions/v2/https");
const { defineSecret } = require("firebase-functions/params");

const apiFootballKey = defineSecret("API_FOOTBALL_KEY");

exports.footballFixtures = onRequest(
  { region: "europe-west1", secrets: [apiFootballKey], timeoutSeconds: 30, memory: "256MiB" },
  async (req, res) => {
    res.set("Cache-Control", "public, max-age=30, s-maxage=30");
    if (req.method !== "GET") return res.status(405).json({ error: "Method not allowed" });

    const date = String(req.query.date || "");
    if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) {
      return res.status(400).json({ error: "date must be YYYY-MM-DD" });
    }
    const timezone = String(req.query.timezone || "UTC");
    try {
      // Validate the IANA timezone supplied by the Android device.
      new Intl.DateTimeFormat("en-US", { timeZone: timezone });
    } catch (_) {
      return res.status(400).json({ error: "Invalid timezone" });
    }

    try {
      const url = new URL("https://v3.football.api-sports.io/fixtures");
      url.searchParams.set("date", date);
      url.searchParams.set("timezone", timezone);
      const upstream = await fetch(url, {
        method: "GET",
        headers: { "x-apisports-key": apiFootballKey.value(), "Accept": "application/json" },
        signal: AbortSignal.timeout(20000)
      });
      const body = await upstream.text();
      if (!upstream.ok) {
        console.error("API-Football upstream status:", upstream.status);
        return res.status(502).json({ error: "Football data provider unavailable" });
      }
      let data;
      try { data = JSON.parse(body); } catch (_) {
        return res.status(502).json({ error: "Invalid provider response" });
      }
      if (data.errors && Object.keys(data.errors).length) {
        console.error("API-Football provider errors:", JSON.stringify(data.errors));
        return res.status(502).json({ error: "Football data provider rejected the request" });
      }
      return res.status(200).json(data);
    } catch (error) {
      console.error("footballFixtures error:", error);
      return res.status(502).json({ error: "Unable to retrieve fixtures" });
    }
  }
);
