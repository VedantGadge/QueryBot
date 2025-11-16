# QueryBot — Conversational SQL Assistant 🤖

QueryBot is a modern web application that transforms natural language questions into SQL queries. Upload your data files, chat with an AI assistant, and get instant results—no SQL knowledge required.

---

## 🚀 Quick Start

### Prerequisites
- **Java 17+** (Spring Boot 3.5.7)
- **PostgreSQL** running on `localhost:5432`
- **Maven** for building
- **OpenRouter API Key** (for LLM integration)

### Setup
1. Clone the repository
2. Configure `src/main/resources/application.properties`:
   ```properties
   spring.datasource.url=jdbc:postgresql://localhost:5432/querybot
   spring.datasource.username=your-postgres-username-here
   spring.datasource.password=your-postgres-password-here
   llm.api.key=your-openrouter-key-here
   ```
3. Run `mvn clean install && mvn spring-boot:run`
4. Open `http://localhost:8080` in your browser

---

## 📂 Project Structure

### Backend (Java/Spring Boot)

#### **Core Application**
- **`QueryBotApplication.java`** — The main Spring Boot entry point. Initializes the application and enables Spring component scanning. Think of it as the engine's ignition switch.

#### **Controllers** (`controller/`)
These handle HTTP requests and route them to appropriate services.

- **`QueryController.java`** — API endpoint hub for NL (natural language) queries
  - `POST /api/query/nl` — Receives natural language questions, orchestrates SQL generation & execution
  - `GET /api/query/history` — Returns conversation history for the current session
  - `POST /api/query/memory` — Stores context facts (uploaded file info, etc.) in session memory

- **`FileUploadController.java`** — Handles file uploads
  - `POST /api/files/upload` — Accepts CSV/Excel/JSON files, parses them, and creates database tables

#### **Services** (`service/`)
Core business logic lives here.

- **`QueryService.java`** — The brains of the operation
  - Converts natural language to SQL via the LLM
  - Executes generated SQL against PostgreSQL
  - Generates human-readable summaries of results
  - Maintains per-session conversation memory (deque of messages)
  - Resolves table names and validates column availability
  - Stores query history in the database

- **`LLMService.java`** — Integrates with OpenRouter API (GPT-4o-mini)
  - Sends NL queries + table schema + system prompts to the LLM
  - Parses LLM responses to extract SQL
  - Includes strict rules: only `SELECT` allowed, no hallucinated columns
  - Has fallback SQL for when API is unavailable

- **`FileService.java`** — Manages file uploads
  - Parses uploaded files (CSV, JSON, Excel)
  - Infers data types from sample values
  - Creates new PostgreSQL tables with proper schemas
  - Stores metadata in the database (table name, columns, row count)

#### **Data Models** (`entity/`)
JPA entities that map to database tables.

- **`QueryHistory.java`** — Audit log of all executed queries
  - `nlQuery` — Original user question
  - `generatedSql` — The SQL that was generated
  - `resultPreview` — JSON preview of results
  - `executedAt` — Timestamp

- **`UploadedTableMetadata.java`** — Registry of uploaded data tables
  - `originalFilename` — User's file name
  - `tableName` — Sanitized database table name
  - `columnsJson` — JSON map of column names & inferred types
  - `rowCount` — Number of rows imported
  - `uploadedAt` — When the file was uploaded

#### **Repositories** (`repository/`)
Spring Data JPA interfaces for database access.

- **`QueryHistoryRepository.java`** — CRUD operations for `QueryHistory`
- **`UploadedTableMetadataRepository.java`** — CRUD operations for table metadata

#### **Utilities** (`util/`)
Helper classes for parsing and validation.

- **`FileParser.java`** — Multi-format file parser
  - Reads CSV (via OpenCSV)
  - Reads JSON (via Jackson)
  - Reads Excel/XLSX (via Apache POI)
  - Returns rows as `List<Map<String, String>>` for uniform processing

- **`SQLValidator.java`** — Security guardian 🛡️
  - Whitelists only `SELECT`, `(`, and `with` (for CTEs)
  - Blocks `INSERT`, `UPDATE`, `DELETE`, `ALTER`, `DROP`, `CREATE`, `TRUNCATE`
  - Prevents comment-based SQL injection

- **`SchemaGenerator.java`** — Infers column data types from samples
  - Tests values against patterns: integer, float, date, timestamp
  - Falls back to `TEXT` for unknowns
  - Generates `CREATE TABLE` SQL

#### **DTOs** (`dto/`)
Request/response objects for API contracts.

- **`NLQueryRequestDTO.java`** — Request body for `/api/query/nl`
  - `nlQuery` — The user's question
  - `targetTable` (optional) — Specific table to query (validated)

- **`NLQueryResponseDTO.java`** — Response with results
  - `sql` — Generated SQL query
  - `rows` — List of result rows as maps
  - `nlAnswer` — Human-readable summary from LLM
  - `message` — Status/error message

- **`UploadResponseDTO.java`** — Response from file upload
  - `tableName` — New table created
  - `rowCount` — Rows imported
  - `message` — Confirmation message

### Frontend (HTML/JavaScript)

#### **`query.html`** — Single-page chat interface
- **Features:**
  - Real-time chat conversation view
  - File upload button (CSV, Excel, JSON)
  - Markdown parsing for AI responses (bold, italic, lists)
  - Modal display for SQL queries and result tables
  - Session memory: loads conversation history on page load
  - Upload confirmation modal (replaces native `confirm()`)
  
- **Key JavaScript Functions:**
  - `appendMessage()` — Renders chat messages (user or assistant)
  - `loadHistory()` — Fetches and renders conversation history from server
  - `parseMarkdown()` — Converts markdown syntax to HTML (e.g., `**bold**` → `<strong>`)
  - `composer.submit` — Sends NL query to `/api/query/nl`, displays results
  - `fileInput.change` — Shows upload confirmation modal
  - `openModal()` / `closeModal()` — Displays SQL & results in a larger view

#### **`index.html`** — Landing page (minimal redirect to chat)

### Configuration

#### **`application.properties`** — Spring Boot configuration
```properties
spring.datasource.url=jdbc:postgresql://localhost:5432/querybot
spring.datasource.username=postgres
spring.datasource.password=vedant1512
llm.api.key=sk-or-v1-...  # OpenRouter API key
llm.api.url=https://openrouter.ai/api/v1/chat/completions
spring.jpa.hibernate.ddl-auto=update  # Auto-create/update DB schema
```

#### **`pom.xml`** — Maven dependencies
- **Spring Boot 3.5.7** — Web framework
- **PostgreSQL Driver** — Database connector
- **Lombok** — Reduces boilerplate (getters/setters)
- **OpenCSV** — CSV parsing
- **Apache POI** — Excel parsing
- **Jackson** — JSON processing
- **Spring Data JPA** — ORM & database access

---

## 🔄 How It Works: The Query Pipeline

```
User Question
     ↓
QueryController.nlQuery()
     ↓
QueryService.executeNlQueryWithSummary()
     ├─ Resolve latest uploaded table
     ├─ Fetch table schema (columns & types)
     ├─ Load conversation memory (prior context)
     ├─ Build LLM prompt: "Here's the schema. Generate SQL for: [question]"
     ↓
LLMService.generateSqlFromNl()
     ├─ Call OpenRouter GPT-4o-mini API
     ├─ Parse response for SQL
     ├─ Return SQL string
     ↓
SQLValidator.isSelectOnly()
     ├─ Ensure only SELECT (or wrapped queries)
     ├─ Block dangerous keywords
     ├─ Validate or throw error
     ↓
JdbcTemplate.queryForList()
     ├─ Execute SQL against PostgreSQL
     ├─ Return rows as List<Map<String, Object>>
     ↓
LLMService.summarizeResults()
     ├─ Generate plain English summary: "The top 5 products by sales are..."
     ↓
QueryService.saveHistory()
     ├─ Store Q&A in `query_history` table
     ├─ Add messages to session memory
     ↓
NLQueryResponseDTO
     ├─ sql: Generated SQL
     ├─ rows: Result data
     ├─ nlAnswer: Summary text
     ↓
Browser (query.html)
     ├─ Render chat message: question & response
     ├─ Auto-show SQL+table if user said "show" or "display"
     ├─ Modal for detailed inspection
```

---

## 🎯 Key Features

### Natural Language → SQL
- **Smart Prompting:** Sends full table schema, available columns, and past queries to LLM
- **Multi-Query Support:** Handles complex queries (UNION, subqueries, GROUP BY)
- **Strict Validation:** SQL Validator blocks destructive operations before execution

### File Upload & Import
- **Multi-Format:** Parses CSV, JSON, and Excel files seamlessly
- **Type Inference:** Detects integer, float, date columns automatically
- **Dynamic Schema:** Creates PostgreSQL tables on-the-fly with proper data types
- **Metadata Tracking:** Stores table info for future reference

### Conversation Memory
- **Per-Session Context:** Each browser session maintains its own conversation history
- **Smart Context:** LLM gets previous Q&As to improve accuracy
- **Reload Friendly:** History persists in database; reloading shows prior messages

### Interactive UI
- **Chat Interface:** Familiar conversation format
- **Markdown Support:** Bold, italic, lists in responses
- **Modal Details:** View SQL and result tables in an expanded view
- **Confirmation Dialogs:** Custom modal for file uploads (UX improvement)

---

## 🔐 Security Considerations

1. **SQL Validation** — Only `SELECT` queries allowed via `SQLValidator`
2. **No Column Hallucination** — LLM strictly told to use only available columns
3. **Parameterized Queries** — Spring's `JdbcTemplate` handles escaping
4. **File Upload Size** — Consider adding `spring.servlet.multipart.max-file-size`
5. **API Key Protection** — Store `llm.api.key` in environment variables, not git

---

## 📊 Database Schema

### `uploaded_table_metadata`
Tracks user-uploaded data tables.
```sql
CREATE TABLE uploaded_table_metadata (
  id BIGINT PRIMARY KEY,
  original_filename VARCHAR,
  table_name VARCHAR UNIQUE,
  columns_json TEXT,  -- JSON: {"Name": "Name (text)", "Age": "Age (integer)", ...}
  row_count INT,
  uploaded_at TIMESTAMP
);
```

### `query_history`
Audit log of executed queries.
```sql
CREATE TABLE query_history (
  id BIGINT PRIMARY KEY,
  nl_query TEXT,
  generated_sql TEXT,
  result_preview TEXT,  -- JSON preview
  executed_at TIMESTAMP
);
```

---

## 🛠️ Development Tips

### Running Tests
```bash
mvn test
```
Tests exist in `src/test/java/com/vedant/querybot/`.

### Local Debug
1. Set `spring.jpa.show-sql=true` in `application.properties` to see SQL
2. Logs available at `INFO` level (see `QueryService`, `LLMService`)
3. Use browser DevTools (F12) to inspect `/api/query/nl` requests/responses

### Extending Features
- **Add New File Format:** Update `FileParser.parse()` method
- **Change LLM Model:** Update `LLMService.generateSqlFromNl()` payload ("model" field)
- **Improve Schema Detection:** Enhance `SchemaGenerator` logic
- **Add User Authentication:** Extend `QueryController` with `@Authentication` and user IDs

---

## 📝 Example Workflow

1. **User uploads** `sales.csv`:
   ```
   Product,Amount,Category
   Widget,1500,Hardware
   Gadget,2500,Electronics
   ```

2. **System creates** table `sales_1731234567` with columns: `Product (text)`, `Amount (integer)`, `Category (text)`

3. **User asks:** "What's the most expensive item?"

4. **LLM generates:**
   ```sql
   SELECT Product, Amount FROM sales_1731234567
   ORDER BY Amount DESC LIMIT 1
   ```

5. **Backend executes**, gets result: `[{Product: "Gadget", Amount: 2500}]`

6. **LLM summarizes:** "The most expensive item is Gadget at $2500."

7. **UI displays** both the natural answer and (if user said "show") the SQL + table view

---

## 📚 Tech Stack

| Layer | Technology |
|-------|-----------|
| **Framework** | Spring Boot 3.5.7 |
| **Database** | PostgreSQL |
| **ORM** | Hibernate/Spring Data JPA |
| **LLM** | OpenRouter API (GPT-4o-mini) |
| **Parsing** | OpenCSV, Apache POI, Jackson |
| **Frontend** | Vanilla HTML/CSS/JavaScript |
| **Build** | Maven |

---

## 🚀 Production Checklist

- [ ] Move API keys to environment variables
- [ ] Set `spring.jpa.hibernate.ddl-auto=validate` (don't auto-update schema)
- [ ] Enable HTTPS
- [ ] Add rate limiting for `/api/query/nl` and `/api/files/upload`
- [ ] Implement user authentication & multi-tenant support
- [ ] Add query timeout & result row limits
- [ ] Set up database backups
- [ ] Monitor API usage & costs (OpenRouter charges per token)
- [ ] Implement proper logging & error tracking

---

## 💡 Future Enhancements

- **Multi-Table Joins:** Support querying across multiple uploaded tables
- **Query Visualization:** Charts & graphs for numerical results
- **Saved Queries:** Bookmark and re-run favorite queries
- **Export Results:** CSV/PDF download option
- **Query Analytics:** Dashboard showing most-asked questions
- **Advanced Filtering:** UI controls for filtering results without rephrasing

---


**Built with ❤️ by VG using Spring Boot & AI**

