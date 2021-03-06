package fr.fouss.boardeo;

import android.os.Build;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;
import android.support.v7.widget.Toolbar;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.Date;
import java.util.TreeMap;

import fr.fouss.boardeo.data.Board;
import fr.fouss.boardeo.data.Post;
import fr.fouss.boardeo.utils.UserUtils;

public class NewPostActivity extends AppCompatActivity {

    /**
     * Firebase database instance
     */
    private DatabaseReference mDatabase;

    private UserUtils userUtils;

    private String postKey;
    private String boardKey;
    private Post post;

    ///// LIFECYCLE /////

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_new_post);

        // database
        mDatabase = FirebaseDatabase.getInstance().getReference();
        userUtils = new UserUtils(this);

        // Setup the toolbar
        setSupportActionBar(findViewById(R.id.NewPostToolbar));
        if (getSupportActionBar() != null)
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        // Setup validation button
        findViewById(R.id.validatePostEditionButton).setOnClickListener(this::onValidateButtonClick);

        // Get the board's key if it exists, null otherwise
        postKey = getIntent().getStringExtra(Post.KEY_FIELD);
        boardKey = getIntent().getStringExtra(Board.KEY_FIELD);
        updateFields();
    }

    private void updateFields() {
        Toolbar toolbar = findViewById(R.id.NewPostToolbar);

        if (boardKey != null) {

            DatabaseReference boardDataReference = mDatabase.child("boards").child(boardKey);
            boardDataReference.addListenerForSingleValueEvent(new ValueEventListener() {
                @Override
                public void onDataChange(DataSnapshot dataSnapshot) {
                    Board board = dataSnapshot.getValue(Board.class);
                    assert board != null;

                    findViewById(R.id.appbar).setBackgroundColor(board.getColor().intValue());
                    toolbar.setBackgroundColor(board.getColor().intValue());
                }

                @Override
                public void onCancelled(DatabaseError databaseError) {
                    Toast.makeText(NewPostActivity.this,
                            "Post info couldn't be retrieved",
                            Toast.LENGTH_SHORT).show();
                }
            });

            if (postKey != null) {

                DatabaseReference dataReference = mDatabase.child("posts").child(postKey);
                dataReference.addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(DataSnapshot dataSnapshot) {
                        post = dataSnapshot.getValue(Post.class);
                        assert post != null;

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            toolbar.setTitle(post.getTitle());
                            toolbar.setSubtitle(R.string.edit_post);
                        }

                        // Setup fields based on request intent
                        EditText title = findViewById(R.id.post_title_field);
                        title.setText(post.getTitle());
                        EditText content = findViewById(R.id.post_content_field);
                        content.setText(post.getContent());
                    }

                    @Override
                    public void onCancelled(DatabaseError databaseError) {
                        Toast.makeText(NewPostActivity.this,
                                "Post info couldn't be retrieved",
                                Toast.LENGTH_SHORT).show();
                    }
                });
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
                    toolbar.setSubtitle(R.string.create_post);
            }
        }

    }

    ///// EVENTS /////

    /**
     * When validation floating button is clicked
     * @param v view
     */
    public void onValidateButtonClick(View v) {
        // retrieve fields
        EditText title = findViewById(R.id.post_title_field);
        EditText content = findViewById(R.id.post_content_field);

        boolean filled = true;
        if (title.length() == 0) {
            title.setError("Missing");
            filled = false;
        }
        if (content.length() == 0) {
            content.setError("Missing");
            filled = false;
        }
        if (filled) {
            validateResult();
        }
    }

    /**
     * Validates data and updates the database
     */
    public void validateResult() {
        // retrieve fields
        String title = ((EditText) findViewById(R.id.post_title_field)).getText().toString();
        String content = ((EditText) findViewById(R.id.post_content_field)).getText().toString();

        // If this is an update
        if (postKey != null) {
            post.setTitle(title);
            post.setContent(content);

            TreeMap<String, Object> updates = new TreeMap<>();
            updates.put("title", title);
            updates.put("content", content);
            mDatabase.child("posts")
                    .child(postKey)
                    .updateChildren(updates);

        // If this is a board creation
        } else {
            long timestamp = new Date().getTime();
            post = new Post(title, content, timestamp, userUtils.getUserUid(), boardKey);
            String newPostKey = mDatabase.child("posts").push().getKey();
            mDatabase.child("posts")
                    .child(newPostKey)
                    .setValue(post);
            mDatabase.child("boards")
                    .child(boardKey)
                    .child("posts")
                    .child(newPostKey)
                    .child("timestamp")
                    .setValue(timestamp);
            mDatabase.child("boards")
                    .child(boardKey)
                    .child("lastUpdate")
                    .setValue(timestamp);
        }
        finish();
    }

    @Override
    public void onBackPressed() {
        onSupportNavigateUp();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}
