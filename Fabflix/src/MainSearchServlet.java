import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import javax.annotation.Resource;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.sql.DataSource;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

@WebServlet(name = "MainSearchServlet", urlPatterns = "/api/mainsearch")
public class MainSearchServlet extends HttpServlet {

    // Create a dataSource which registered in web.xml
    @Resource(name = "jdbc/moviedb")
    private DataSource dataSource;
    private Map<String,Integer> stopWords = new HashMap<String,Integer>();

    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        long TSStart = System.nanoTime();
        response.setContentType("application/json");

        PrintWriter out = response.getWriter();
        buildStopWords();

        String[] tokens = request.getParameter("main").split(" ", 0);

        ArrayList<String> noSWTokens = new ArrayList<String>();
        for (int i = 0; i < tokens.length; i++) {
            if (stopWords.get(tokens[i]) == null) {
                noSWTokens.add(tokens[i]);
            }
        }

        try {
            long TJSConnect = System.nanoTime();
            Connection dbcon = dataSource.getConnection();
            long TJEConnect = System.nanoTime();
            long TJConnect = TJEConnect - TJSConnect;


            String basic = "SELECT b.movieId AS id, b.year, b.title, b.director,\n" +
                    "substring_index(group_concat(b.starId ORDER by b.cnt DESC,b.name ASC, b.starId SEPARATOR ','),',',3) as starId, mg.genres,\n" +
                    "substring_index(group_concat(b.name ORDER by b.cnt DESC,b.name ASC, b.name SEPARATOR ','),',',3) as actors\n" +
                    "FROM (SELECT sim.starid, sim.movieId,m.title,m.year,m.director,mss.cnt,s.name from movies m, movie_stars_sorted mss, stars_in_movies sim, stars s\n" +
                    "WHERE mss.id = sim.starId AND s.id = sim.starId AND m.id = sim.movieId\n" +
                    "AND MATCH(m.title) AGAINST(";

            for (int i = 0; i < noSWTokens.size(); i++) {
                basic += " ? ";
            }

            basic += " IN BOOLEAN MODE)) as b, movie_genres mg WHERE mg.id = b.movieId GROUP BY b.movieId";
            System.out.println(basic);

            long TJSPrep = System.nanoTime();
            PreparedStatement searchMovie = dbcon.prepareStatement(basic);

            for (int i =0; i < noSWTokens.size(); i++) {
                searchMovie.setString(i+1,"+" + noSWTokens.get(i) + "*");
            }
            ResultSet rs = searchMovie.executeQuery();
            long TJEPrep = System.nanoTime();
            long TJPrep = TJEPrep-TJSPrep;


            JsonArray jsonArray = new JsonArray();

            // Iterate through each row of rs
            while (rs.next()) {
                String movies_id = rs.getString("id");
                String movies_title = rs.getString("title");
                int movies_year = rs.getInt("year");
                String movies_director = rs.getString("director");
                String movies_actors = rs.getString("actors");
                String movies_genres = rs.getString("genres");
                String movies_starIds = rs.getString("starId");

                String getRating = "SELECT * from ratings where movieId = ?";
                PreparedStatement getRatingStmt = dbcon.prepareStatement(getRating);
                getRatingStmt.setString(1,movies_id);
                ResultSet rs1 = getRatingStmt.executeQuery();

                String movies_ratings = "N/A";

                if (rs1.next()) {
                    movies_ratings = rs1.getString("rating");
                }

                rs1.close();


                // Create a JsonObject based on the data we retrieve from rs
                JsonObject jsonObject = new JsonObject();
                jsonObject.addProperty("movies_id", movies_id);
                jsonObject.addProperty("movies_title", movies_title);
                jsonObject.addProperty("movies_year", movies_year);
                jsonObject.addProperty("movies_director",movies_director);
                jsonObject.addProperty("movies_ratings",movies_ratings);
                jsonObject.addProperty("movies_actors",movies_actors);
                jsonObject.addProperty("movies_genres",movies_genres);
                jsonObject.addProperty("movies_starIds",movies_starIds);

                jsonArray.add(jsonObject);
            }

            // write JSON string to output
            out.write(jsonArray.toString());
            // set response status to 200 (OK)
            response.setStatus(200);

            rs.close();

            long TJSClose = System.nanoTime();
            searchMovie.close();
            dbcon.close();
            long TJEClose = System.nanoTime();
            long TJClose = TJEClose - TJSClose;

            long TSEnd = System.nanoTime();

            long TSElapsed = TSEnd - TSStart;
            long TJElapsed = TJConnect + TJPrep + TJClose;

            String contextPath = getServletContext().getRealPath("/");
            String filepath = contextPath + "\\tstjlog.txt";
            File myFile = new File(filepath);
            myFile.createNewFile();
            FileWriter fw = new FileWriter(filepath,true);
            PrintWriter pw = new PrintWriter(fw);
            String s = String.format("%s %s",TSElapsed,TJElapsed);
            pw.println(s);
            pw.close();

        } catch (Exception e) {
            System.out.println(e.getMessage());
            // write error message JSON object to output
            JsonObject jsonObject = new JsonObject();
            jsonObject.addProperty("errorMessage", e.getMessage());
            out.write(jsonObject.toString());

            // set reponse status to 500 (Internal Server Error)
            response.setStatus(500);

        }
        out.close();

    }

    private void buildStopWords() {
        try {
            Connection dbcon = dataSource.getConnection();
            String getStopWordsString = "SELECT * FROM INFORMATION_SCHEMA.INNODB_FT_DEFAULT_STOPWORD";
            PreparedStatement getStopWords = dbcon.prepareStatement(getStopWordsString);
            ResultSet rs = getStopWords.executeQuery();

            while (rs.next()) {
                stopWords.put(rs.getString(1),1);
            }

            rs.close();
            getStopWords.close();
            dbcon.close();

        } catch (Exception e) {
            System.out.println(e.getMessage());
        }
    }
}
